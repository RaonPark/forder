# Payment Service 동시성 문제 분석 및 테스트 가이드

## 배경

Payment Service는 두 가지 진입점에서 동시성 문제가 발생할 수 있다.

1. **REST API** (`requestRefund`): 클라이언트가 동시에 같은 결제에 환불을 요청하는 경우
2. **Saga (Kafka)** (`processPayment`, `refundPayment`): Kafka at-least-once 전달 보장으로 동일한 커맨드가 중복 소비되거나, 여러 인스턴스가 동시에 같은 커맨드를 처리하는 경우

---

## 문제 1: processPayment — 동시 INSERT 경쟁

### 왜 발생하는가

Kafka는 at-least-once 전달을 보장한다. 즉 같은 커맨드가 두 번 이상 소비될 수 있다.
또한 서비스가 여러 인스턴스로 실행될 때 파티션 리밸런싱 직전·직후 순간에 동일한 메시지를 두 인스턴스가 동시에 읽는 경우가 있다.

```
┌─ Instance A ───────────────────────────────┐
│ findBySagaId("saga-X") → null              │  ← 아직 저장 안 됨
│ Payment(sagaId="saga-X") → save() → ✅ 성공  │
└────────────────────────────────────────────┘

┌─ Instance B ───────────────────────────────┐
│ findBySagaId("saga-X") → null              │  ← A가 저장하기 전에 조회
│ Payment(sagaId="saga-X") → save()          │
│   → 💥 DuplicateKeyException               │  ← A가 이미 저장함
└────────────────────────────────────────────┘
```

애플리케이션 레벨의 `findBySagaId → null 확인 → save` 흐름은 **원자적이지 않다.**
두 인스턴스가 동시에 "null 확인"을 통과하면 둘 다 INSERT를 시도한다.

### 어떻게 해결했는가

**이중 방어 전략**을 사용한다.

**1차 방어 (애플리케이션 레벨)**: 빠른 경로로 이미 처리된 sagaId를 조기 반환

```kotlin
paymentRepository.findBySagaId(sagaId)?.let {
    return it.paymentId  // 이미 처리됨 → 즉시 반환
}
```

**2차 방어 (DB 레벨)**: `sagaId`에 sparse unique index 설정

```kotlin
// MongoIndexConfiguration.kt
val sagaIdIndex = Index()
    .on("sagaId", Sort.Direction.ASC)
    .unique()
    .sparse()  // null은 인덱싱 제외 → REST API 결제(sagaId=null)와 충돌 없음
```

1차 방어를 통과한 동시 요청이 INSERT를 시도하면 DB가 `DuplicateKeyException`을 던진다.
이를 잡아서 이미 저장된 결제를 조회해 반환한다.

```kotlin
// PaymentService.kt
return try {
    val saved = paymentRepository.save(payment)
    saved.paymentId
} catch (e: DuplicateKeyException) {
    // 2차 방어: 동시 처리로 unique index 위반 → 이미 저장된 결제 반환
    paymentRepository.findBySagaId(sagaId)?.paymentId ?: throw e
}
```

**왜 sparse index인가?**

`sagaId`는 REST API로 생성된 결제에는 없고 (null), Saga로 생성된 결제에만 있다.
일반 unique index는 `null`도 유니크하게 취급하므로 `sagaId=null`인 문서가 2개 이상 존재하면 오류가 난다.
Sparse index는 `null` 필드를 인덱싱에서 제외하므로 이 문제를 피할 수 있다.

### 테스트

```kotlin
@Test
fun `같은 sagaId로 동시에 10개 요청 - 1건만 저장되고 모두 동일한 paymentId 반환`() {
    val sagaId      = "saga-${UUID.randomUUID()}"
    val concurrency = 10
    val barrier     = CyclicBarrier(concurrency)   // ← 동시 출발 장치
    val results     = ConcurrentLinkedQueue<Result<String>>()
    val latch       = CountDownLatch(concurrency)  // ← 전원 완료 대기 장치

    repeat(concurrency) {
        Thread {
            barrier.await()  // 10개 스레드가 여기서 대기 → 동시 출발
            results += runBlocking {
                runCatching { paymentService.processPayment(sagaId, ...) }
            }
            latch.countDown()
        }.start()
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS))

    // 기대 결과 ① 모두 성공
    assertTrue(results.none { it.isFailure })
    // 기대 결과 ② 모두 동일한 paymentId 반환 (멱등성)
    assertEquals(1, results.map { it.getOrThrow() }.distinct().size)
    // 기대 결과 ③ DB에 1건만 저장
    assertEquals(1L, paymentRepository.count())
}
```

---

## 문제 2: requestRefund — 동시 UPDATE 경쟁 (낙관적 락)

### 왜 발생하는가

REST API를 통해 클라이언트가 실수로 또는 네트워크 재시도로 동시에 같은 결제에 환불 요청을 두 번 보낼 수 있다.

```
┌─ Thread A ─────────────────────────────────┐
│ findById("pay-1") → payment(version=0)     │
│ paymentRefundRepository.save(refundA) ✅    │
│ paymentRepository.save(payment, ver=0)     │
│   → DB: version 0 → 1 ✅                   │
└────────────────────────────────────────────┘

┌─ Thread B ─────────────────────────────────┐
│ findById("pay-1") → payment(version=0)     │  ← A가 커밋하기 전에 읽음
│ paymentRefundRepository.save(refundB) ✅    │
│ paymentRepository.save(payment, ver=0)     │
│   → DB: version은 이미 1                   │
│   → 💥 OptimisticLockingFailureException   │
└────────────────────────────────────────────┘
```

### 어떻게 해결했는가

`Payment` 도큐먼트에 `@Version` 어노테이션을 사용한다.

```kotlin
// Payment.kt
@Version
var version: Long? = null
```

Spring Data MongoDB는 `save()` 시 `WHERE version = <읽었을 때의 버전>` 조건을 붙인다.
다른 스레드가 먼저 저장해 버전이 올라갔다면 업데이트 대상이 0건 → `OptimisticLockingFailureException`을 던진다.

이 예외는 서비스에서 잡지 않고 **그대로 전파**한다.
`GlobalExceptionHandler`가 HTTP 409 Conflict로 변환해 클라이언트에 재시도를 유도한다.

```kotlin
// GlobalExceptionHandler.kt
@ExceptionHandler(OptimisticLockingFailureException::class)
fun handleOptimisticLocking(e: OptimisticLockingFailureException): ResponseEntity<ErrorResponse> {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse("CONCURRENT_CONFLICT", "동시 요청이 충돌했습니다. 다시 시도해주세요."))
}
```

**왜 비관적 락이 아닌 낙관적 락인가?**

결제 환불 충돌은 실제로 드물게 발생하는 케이스다.
비관적 락은 모든 요청에 DB 락을 걸어 정상 케이스의 처리량을 낮춘다.
낙관적 락은 충돌이 없는 정상 케이스에서 오버헤드가 없고, 충돌 시에만 클라이언트 재시도를 요청한다.

### 테스트

```kotlin
@Test
fun `APPROVED 결제에 동시에 2개 환불 요청 - 1개 성공, 1개 OptimisticLockingFailureException`() {
    runBlocking { paymentRepository.save(approvedPayment(paymentId = paymentId)) }

    val barrier = CyclicBarrier(2)
    val results = ConcurrentLinkedQueue<Result<*>>()
    val latch   = CountDownLatch(2)

    repeat(2) {
        Thread {
            barrier.await()  // 동시 출발
            results += runBlocking {
                runCatching { paymentService.requestRefund(paymentId, refundRequest) }
            }
            latch.countDown()
        }.start()
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS))

    // 기대 결과 ① 정확히 하나만 성공
    assertEquals(1, results.count { it.isSuccess })
    // 기대 결과 ② 나머지 하나는 OptimisticLockingFailureException
    assertIs<OptimisticLockingFailureException>(results.first { it.isFailure }.exceptionOrNull())
    // 기대 결과 ③ DB 상태는 CANCELED
    assertEquals(PaymentStatus.CANCELED, paymentRepository.findById(paymentId)?.status)
}
```

---

## 문제 3: refundPayment (Saga) — 동시 환불 INSERT 경쟁

### 왜 발생하는가

`processPayment`와 동일한 구조다. Kafka 재전달이나 동시 처리로 같은 `sagaId`의 환불 커맨드가 두 번 이상 실행될 수 있다.

```
┌─ Thread 1~N (모두 동시 출발) ─────────────────┐
│ existsBySagaId("saga-X") → false (모두)        │  ← 아직 아무것도 저장 안 됨
│ PaymentRefund(sagaId="saga-X") → save()       │
│   Thread 1: ✅ 저장 성공                       │
│   Thread 2~N: 💥 DuplicateKeyException        │  ← sagaId unique index 위반
│              → early return (멱등성)           │
│ Thread 1: payment → save(CANCELED) ✅          │
└────────────────────────────────────────────────┘
```

### 버그와 수정

초기 구현에서 `existsBySagaId` 체크와 `save()` 사이의 gap에서 발생하는 `DuplicateKeyException`을 처리하지 않았다.
동시 처리 시 1차 방어(`existsBySagaId → false`)를 모두 통과한 뒤 INSERT 경쟁이 일어나면 처리되지 않은 예외가 전파되는 버그가 있었다.

```kotlin
// 수정 전: DuplicateKeyException 미처리
paymentRefundRepository.save(refund)
paymentRepository.save(payment.copy(status = PaymentStatus.CANCELED))

// 수정 후: processPayment와 동일한 이중 방어 패턴 적용
try {
    paymentRefundRepository.save(refund)
} catch (e: DuplicateKeyException) {
    // 2차 방어: 동시 처리로 sagaId unique index 위반 → 멱등성 보장
    log.warn("[Saga] sagaId 중복 환불 감지(동시 처리) - sagaId={}", sagaId)
    return  // 이미 저장된 것으로 간주, payment 업데이트는 성공한 스레드가 담당
}
paymentRepository.save(payment.copy(status = PaymentStatus.CANCELED))
```

### 테스트

```kotlin
@Test
fun `같은 sagaId로 동시에 10개 환불 요청 - 1건만 저장되고 모두 예외 없이 완료`() {
    runBlocking { paymentRepository.save(approvedPayment(orderId = orderId)) }

    val barrier = CyclicBarrier(10)
    val results = ConcurrentLinkedQueue<Result<*>>()
    val latch   = CountDownLatch(10)

    repeat(10) {
        Thread {
            barrier.await()
            results += runBlocking {
                runCatching { paymentService.refundPayment(sagaId, orderId, "10000") }
            }
            latch.countDown()
        }.start()
    }

    assertTrue(latch.await(30, TimeUnit.SECONDS))

    // 기대 결과 ① 모두 예외 없이 완료 (Saga 커맨드는 실패하면 안 됨)
    assertTrue(results.none { it.isFailure })
    // 기대 결과 ② 환불은 1건만 저장
    assertEquals(1L, paymentRefundRepository.count())
    // 기대 결과 ③ 결제 상태는 CANCELED
    assertEquals(PaymentStatus.CANCELED, paymentRepository.findByOrderId(orderId)?.status)
}
```

---

## 동시성 테스트 패턴

### 왜 단위 테스트(MockK)로는 부족한가

MockK는 Repository를 모킹한다. 아무리 스레드를 동시에 실행해도 모킹된 `save()`는 실제 DB 제약(unique index, version 충돌)을 재현하지 못한다. `DuplicateKeyException`이나 `OptimisticLockingFailureException`을 발생시키려면 `throws`로 직접 설정해야 하는데, 이는 "이미 예외가 발생한다고 가정"하는 것이지 "실제로 발생하는지 검증"하는 것이 아니다.

```
단위 테스트(MockK)        → 로직 분기 검증 (예외 발생 시 어떻게 처리하는가)
통합 테스트(Testcontainers) → DB 보호 검증 (실제로 DB가 예외를 던지는가)
```

두 테스트는 서로 보완 관계다.

### CyclicBarrier + Thread + CountDownLatch 패턴

```
CyclicBarrier(N)
  → N개 스레드가 barrier.await()에서 대기
  → 마지막 스레드가 도착하는 순간 전원 동시 출발
  → 최대한 동시에 DB에 요청을 던지는 상황을 만든다

CountDownLatch(N)
  → 메인 스레드가 latch.await()에서 대기
  → 각 스레드가 완료될 때 latch.countDown()
  → 모든 스레드가 완료되면 메인 스레드가 검증을 시작한다

runBlocking { }
  → 각 Thread에서 suspend 함수를 블로킹으로 실행
  → Kotlin Coroutine 기반 서비스를 일반 Thread 환경에서 호출할 때 사용
```

### 왜 Coroutine launch가 아닌 Thread를 사용하는가

Coroutine은 기본적으로 같은 스레드 풀에서 실행된다. Dispatcher 설정에 따라 실제 병렬 실행이 보장되지 않을 수 있다.
DB 레벨의 동시성 충돌을 재현하려면 실제로 여러 OS 스레드가 동시에 DB 요청을 보내야 한다.
`Thread`를 직접 생성하면 JVM 수준에서 병렬 실행이 보장된다.

---

## 통합 테스트 인프라 — Spring Boot 4.0 트랜잭션 문제

### 문제

Spring Boot 4.0은 `ReactiveMongoTransactionManager`를 자동 설정한다.
`@Transactional` 메서드가 실행되면 MongoDB 트랜잭션을 시작하는데,
Testcontainers의 standalone `MongoDBContainer`는 이를 거부한다.

```
MongoCommandException: Transaction numbers are only allowed on a replica set member or mongos
```

MongoDB 트랜잭션은 Replica Set 또는 Sharded Cluster에서만 동작한다.

### 왜 Replica Set으로 전환하지 않았는가

Testcontainers에서 `MongoDBContainer`에 `--replSet rs0` 옵션을 주고 `rs.initiate()`를 실행하는 방법이 있다. 시도했으나 `MongoTimeoutException`이 발생했고, `@DynamicPropertySource`로 MongoDB URI를 주입하려 해도 `application.yml`의 `spring.data.mongodb.uri`가 재정의되지 않는 문제가 있었다.

무엇보다 이 테스트에서 MongoDB 트랜잭션 자체를 검증할 필요가 없다.

### 실제로 보호하는 것이 무엇인가

| 보호 수단 | 동작 위치 | 트랜잭션 필요 여부 |
|---|---|---|
| `@Version` (낙관적 락) | MongoDB 드라이버 레벨 | **불필요** |
| `sagaId` sparse unique index | MongoDB DB 레벨 | **불필요** |

두 보호 수단 모두 트랜잭션 없이 MongoDB 드라이버 레벨에서 동작한다.
따라서 트랜잭션을 no-op으로 만들어도 테스트 목적에 부합한다.

### 해결: NoOpReactiveTransactionManager

```kotlin
@TestConfiguration
class NoOpTransactionConfig {

    @Bean
    @Primary  // Spring Boot 자동 설정된 ReactiveMongoTransactionManager를 대체
    fun testReactiveTransactionManager(): ReactiveTransactionManager =
        object : ReactiveTransactionManager {
            private val noOpTransaction = object : ReactiveTransaction {
                override fun isNewTransaction() = true
                override fun setRollbackOnly() {}
                override fun isRollbackOnly()   = false
                override fun isCompleted()      = false
            }
            // 트랜잭션 시작/커밋/롤백 모두 아무것도 하지 않음
            override fun getReactiveTransaction(definition: TransactionDefinition?) =
                Mono.just(noOpTransaction)
            override fun commit(transaction: ReactiveTransaction)   = Mono.empty<Void>()
            override fun rollback(transaction: ReactiveTransaction) = Mono.empty<Void>()
        }
}
```

```kotlin
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.kafka.listener.auto-startup=false"]  // Kafka listener 비활성화
)
@Import(TestcontainersConfiguration::class, NoOpTransactionConfig::class)
class PaymentServiceIntegrationTest { ... }
```

`@Transactional`의 경계는 유지되되 실제 MongoDB 트랜잭션은 열리지 않는다.

### 전체 테스트 실행 시 Context 충돌 문제

`PaymentServiceApplicationTests`는 `NoOpTransactionConfig` 없이 `@SpringBootTest`를 사용한다.
Spring은 Context 캐시 키(설정이 다른 경우 별도 Context 생성)를 기준으로 Context를 재사용하는데,
두 클래스의 `@SpringBootTest` 설정이 달라 별도 Context가 생성되는 과정에서 충돌이 발생할 수 있다.

**해결**: `PaymentServiceApplicationTests`에도 동일한 설정을 추가한다.

```kotlin
@Import(TestcontainersConfiguration::class, NoOpTransactionConfig::class)
@SpringBootTest(properties = ["spring.kafka.listener.auto-startup=false"])
class PaymentServiceApplicationTests {
    @Test fun contextLoads() {}
}
```

---

## 정리: 동시성 보호 전략 한눈에 보기

| 시나리오 | 보호 수단 | 결과 |
|---|---|---|
| Saga 결제 중복 처리 | sagaId sparse unique index + DuplicateKeyException catch | 모두 성공, 동일 paymentId 반환 |
| REST API 환불 동시 요청 | @Version 낙관적 락 | 1개 성공, 나머지 409 Conflict |
| Saga 환불 중복 처리 | sagaId sparse unique index + DuplicateKeyException catch | 모두 성공 (early return), 환불 1건 저장 |
