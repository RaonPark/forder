# Order Service 복원력 설계 — 이론과 도메인 결정 해설

> 이 문서는 order-service의 Kafka 신뢰성, Saga 타임아웃, 취소 경쟁 조건 등
> 실제 운영 환경에서 발생할 수 있는 문제들을 어떻게 설계로 해결했는지 기록한다.
> "왜 이렇게 만들었는가"에 초점을 맞춘다.

---

## 목차

1. [Kafka 수동 ACK와 shouldAck 패턴](#1-kafka-수동-ack와-shouldack-패턴)
2. [낙관적 잠금(Optimistic Locking)과 Kafka 재시도](#2-낙관적-잠금optimistic-locking과-kafka-재시도)
3. [Dead Letter Topic(DLT) 전략](#3-dead-letter-topicdlt-전략)
4. [Jitter Exponential Backoff — Thundering Herd 방지](#4-jitter-exponential-backoff--thundering-herd-방지)
5. [Saga 타임아웃 문제](#5-saga-타임아웃-문제)
6. [SagaTimeoutService — 탐지 로직과 임계값 설계](#6-sagатimeoutservice--탐지-로직과-임계값-설계)
7. [SagaTimeoutHandler — 트랜잭션 격리와 Terminal-First 패턴](#7-sagatimeouthandler--트랜잭션-격리와-terminal-first-패턴)
8. [상태별 Best-Effort 보상 전략](#8-상태별-best-effort-보상-전략)
9. [PREPARING 상태 취소 — 알려진 경쟁 조건과 설계 결정](#9-preparing-상태-취소--알려진-경쟁-조건과-설계-결정)
10. [Airflow vs Spring @Scheduled — 스케줄러 선택 이유](#10-airflow-vs-spring-scheduled--스케줄러-선택-이유)
11. [InternalSagaController — 내부 전용 엔드포인트 보안 설계](#11-internalsagacontroller--내부-전용-엔드포인트-보안-설계)

---

## 1. Kafka 수동 ACK와 shouldAck 패턴

### 배경: 자동 ACK의 위험성

Kafka Consumer는 메시지를 받은 후 "처리 완료"를 브로커에게 알리는 **ACK(오프셋 커밋)**를 수행해야 한다.
기본 자동 ACK 모드(`BATCH` or `RECORD`)는 메시지를 받은 직후 바로 오프셋을 커밋한다.

```
[자동 ACK의 문제]
1. Kafka에서 메시지 수신 → 자동 오프셋 커밋 (이 시점에 Kafka는 "처리됨"으로 간주)
2. 비즈니스 로직 실행 중 DB 오류 발생
3. 메시지가 사라짐 → 데이터 손실
```

이 시스템에서 Saga Reply 메시지 하나를 잃으면 주문이 영구히 중간 상태로 머문다.
따라서 `MANUAL_IMMEDIATE` 모드를 사용하여 비즈니스 로직이 완전히 성공한 후에만 ACK한다.

### shouldAck 패턴

단순 수동 ACK만으로는 충분하지 않다.
예외의 종류에 따라 ACK 여부를 다르게 처리해야 한다.

```kotlin
// KafkaConfig.kt
factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE

// OrderSagaOrchestrator.kt
var shouldAck = true
try {
    // 비즈니스 로직 ...
} catch (e: OptimisticLockingFailureException) {
    shouldAck = false   // ← Kafka가 재시도하도록 ACK 하지 않음
    throw e
} finally {
    if (shouldAck) ack.acknowledge()  // 그 외 예외는 ACK (DLT로 라우팅)
}
```

**예외 종류별 처리 방향:**

| 예외 | ACK 여부 | 이유 |
|------|----------|------|
| `OptimisticLockingFailureException` | **ACK 안 함** | 일시적 충돌. Kafka가 재시도하면 해결 가능 |
| `RuntimeException` 등 기타 | **ACK 함** | 재시도해도 해결 불가능. DLT로 보내 파티션 블로킹 방지 |

### 파티션 블로킹 문제

ACK를 하지 않으면 해당 파티션의 **다음 메시지들이 모두 블로킹**된다.
따라서 "절대 해결 불가능한 예외"(예: 역직렬화 오류, null 포인터)까지 ACK 안 하면
그 파티션 전체가 영구히 멈춘다.

```
[파티션 블로킹 예시]
파티션 0: [메시지A(오류)] [메시지B] [메시지C] ...
                ↑ 이 메시지가 ACK 안 되면 B, C는 영원히 처리 안 됨
```

`shouldAck = false`는 **재시도로 해결 가능한 예외에만** 적용해야 하는 이유다.

---

## 2. 낙관적 잠금(Optimistic Locking)과 Kafka 재시도

### 낙관적 잠금이란

MongoDB의 `@Version` 필드를 이용한 동시성 제어 방식이다.

```kotlin
@Document(collection = "orders")
data class Orders(
    @Id val orderId: String,
    var status: OrderStatus,
    @Version var version: Long? = null  // 버전 필드
)
```

저장 시 DB에 있는 버전과 메모리의 버전이 다르면 `OptimisticLockingFailureException`이 발생한다.

```
[충돌 시나리오]
Consumer A: 주문 조회 (version=1) → 처리 중 ...
Consumer B: 주문 조회 (version=1) → 처리 중 ...
Consumer A: 저장 (version=1 → 2) ✅
Consumer B: 저장 시도 (version=1이지만 DB는 이미 2) → OptimisticLockingFailureException ❌
```

### 왜 Kafka 재시도가 해결책인가

`OptimisticLockingFailureException`은 **다른 Consumer가 이미 처리했거나, 처리 중임**을 의미한다.
ACK를 하지 않고 예외를 던지면 Kafka가 ExponentialBackOff에 따라 재시도한다.

재시도 시점에는:
- 먼저 처리한 Consumer가 이미 상태를 바꿔놓음
- 재시도한 Consumer는 `isTerminated()` 체크로 해당 Saga가 이미 처리됐음을 감지하고 스킵

이것이 **낙관적 잠금 + Kafka 재시도**의 조합이 작동하는 원리다.

---

## 3. Dead Letter Topic(DLT) 전략

### DLT란

재시도를 모두 소진한 메시지를 별도 토픽(`{원본토픽}.DLT`)에 발행하여
파티션 블로킹 없이 나중에 분석·재처리할 수 있도록 하는 패턴이다.

```kotlin
// KafkaConfig.kt
val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate)
val errorHandler = DefaultErrorHandler(recoverer, JitterExponentialBackOff())
factory.setCommonErrorHandler(errorHandler)
```

### 이 시스템에서 DLT 소비자를 구현하지 않은 이유

DLT는 **발행 측(Publisher)만 구현**해두고 소비자(Consumer)는 아직 없다.
이유는 다음과 같다:

1. **Saga 타임아웃 스케줄러(Airflow)가 보완재**가 된다.
   DLT에 빠진 메시지로 인해 주문이 중간 상태에 머물더라도, 30~120분 내에 타임아웃 스케줄러가 탐지하고 보상한다.

2. **DLT 소비자는 비즈니스 복잡도가 높다.**
   단순 재처리가 아니라 현재 주문 상태를 보고 "이 메시지가 아직 유효한가?"를 판단해야 한다.

3. **DLT 메시지는 손실되지 않는다.**
   Kafka 토픽에 보존 기간이 있는 한 나중에 소비자를 추가하여 재처리할 수 있다.

```
[현재 아키텍처]
정상 메시지 → 처리 → ACK
충돌 메시지 → 재시도(ExponentialBackOff) → 성공 or DLT로 발행
DLT 메시지 → 토픽에 보존 (소비자 없음)
멈춘 주문  → Airflow 타임아웃 스케줄러가 30~120분 내 탐지·보상
```

---

## 4. Jitter Exponential Backoff — Thundering Herd 방지

### Thundering Herd 문제

여러 Consumer가 동시에 같은 주문에 대한 충돌을 경험하면, 모두 동일한 간격으로 재시도한다.

```
[FixedBackOff(1000ms)의 문제]
Consumer A: 충돌 → 1000ms 대기 → 재시도 → 또 충돌 (A, B 동시 재시도)
Consumer B: 충돌 → 1000ms 대기 → 재시도 → 또 충돌
Consumer C: 충돌 → 1000ms 대기 → 재시도 → 또 충돌
→ 영원히 충돌 반복 (Thundering Herd)
```

### JitterExponentialBackOff 설계

```kotlin
// KafkaConfig.kt
private class JitterExponentialBackOff(
    initialInterval: Long = 1_000L,   // 초기 대기: 1초
    multiplier: Double    = 2.0,      // 배수: ×2
    maxInterval: Long     = 30_000L,  // 최대 대기: 30초
    maxElapsedTime: Long  = 120_000L, // 총 재시도 시간: 2분
    private val jitterRange: Long = 500L  // 랜덤 편차: ±500ms
) : BackOff {
    override fun start(): BackOffExecution {
        val delegateExecution = delegate.start()
        return object : BackOffExecution {
            override fun nextBackOff(): Long {
                val next = delegateExecution.nextBackOff()
                return if (next == BackOffExecution.STOP) next
                else next + ThreadLocalRandom.current().nextLong(0, jitterRange)
            }
        }
    }
}
```

**재시도 간격 시뮬레이션:**

```
시도 1: 1000 + 0~500ms   ≈ 1.0~1.5초
시도 2: 2000 + 0~500ms   ≈ 2.0~2.5초
시도 3: 4000 + 0~500ms   ≈ 4.0~4.5초
시도 4: 8000 + 0~500ms   ≈ 8.0~8.5초
시도 5: 16000 + 0~500ms  ≈ 16.0~16.5초
시도 6: 30000 + 0~500ms  ≈ 30.0~30.5초 (상한 도달)
...
총 2분 초과 시 → DLT
```

**Jitter 효과:**
Consumer들이 서로 다른 시점에 재시도하게 되어 충돌 확률이 크게 줄어든다.

### `ThreadLocalRandom`을 사용한 이유

`Math.random()`이나 공유 `Random` 인스턴스 대신 `ThreadLocalRandom`을 사용한 이유:
- 스레드별 독립 인스턴스 → 스레드 경합 없음
- Consumer 스레드가 여러 개(`setConcurrency(3)`)인 환경에서 성능 저하 방지

---

## 5. Saga 타임아웃 문제

### 분산 시스템에서 Saga가 멈추는 이유

Orchestration Saga는 여러 서비스의 Reply를 기다리는 구조다.
Reply가 오지 않으면 주문은 영구히 중간 상태에 머문다.

**Reply가 안 오는 시나리오:**
```
1. 다운스트림 서비스 자체가 죽음 (inventory, payment, delivery 장애)
2. Kafka 브로커 장애로 Command가 전달 안 됨
3. Consumer가 DLT로 빠져 Reply 없이 처리됨
4. 코드 버그: 특정 조건에서 Reply를 발행하지 않음
5. 네트워크 파티션: Command 발행 후 서비스가 응답 못함
```

**멈춘 상태의 주문들:**
```
PENDING          → inventory-command를 기다리는 중
CANCELLING       → delivery cancel reply를 기다리는 중
RETURN_REQUESTED → return pickup reply를 기다리는 중
RETURN_IN_PROGRESS → payment refund reply를 기다리는 중
```

### 왜 Saga에 자체 타임아웃 로직이 없는가

Kafka 기반 비동기 Saga에서는 "메시지가 올지 안 올지"를 Orchestrator 자체가 알 방법이 없다.
Command를 발행한 후 Reply를 기다리는 동안 해당 스레드/코루틴은 이미 종료되어 있다.
이것은 HTTP 요청과 달리 **stateless한 비동기 처리**이기 때문이다.

따라서 타임아웃 탐지는 반드시 **외부에서 주기적으로 DB를 스캔**하는 방식이어야 한다.

---

## 6. SagaTimeoutService — 탐지 로직과 임계값 설계

### 상태별 임계값을 다르게 설정한 이유

```kotlin
// SagaTimeoutService.kt
const val CREATION_TIMEOUT_MINUTES     = 30L   // 생성 Saga
const val CANCELLATION_TIMEOUT_MINUTES = 60L   // 취소 Saga
const val RETURN_TIMEOUT_MINUTES       = 120L  // 반품 Saga
```

각 임계값은 해당 Saga의 **정상 완료 시간 × 충분한 여유**로 설정했다.

| Saga | 정상 완료 시간 | 임계값 | 이유 |
|------|--------------|--------|------|
| 생성 | 수 초 | 30분 | 재고·결제·배송 모두 수 초 내 응답. 30분은 매우 보수적 |
| 취소 | 수 분 | 60분 | 배송 취소는 물리적 과정 포함. 더 긴 여유 필요 |
| 반품 | 수 시간 | 120분 | 픽업·검수 과정이 있음. 가장 긴 여유 |

### 단일 쿼리 + 인메모리 재필터링 패턴

```kotlin
// 가장 짧은 임계값(30분)으로 단 한 번 DB 쿼리
val minThreshold = now.minusSeconds(CREATION_TIMEOUT_MINUTES * 60)

orderRepository.findByStatusInAndUpdatedAtBefore(TIMEOUT_STATUSES, minThreshold)
    .collect { order ->
        // 각 주문의 상태별로 정확한 임계값으로 재필터링
        val threshold = thresholdFor(order.status, now)
        if (!lastActivity.isBefore(threshold)) return@collect
        // ...
    }
```

**이렇게 설계한 이유:**
- DB 쿼리를 최소화 (N번 쿼리 대신 1번)
- 인메모리 재필터링은 O(n) 연산으로 부하 없음
- 가장 짧은 임계값(30분)보다 오래된 모든 주문을 가져오되, 각 상태별로 정확하게 필터링

### 개별 주문 처리 격리 (`runCatching`)

```kotlin
runCatching { timeoutHandler.handle(order) }
    .onSuccess { outcome -> /* 결과 집계 */ }
    .onFailure { e ->
        log.error("[Timeout] Failed to process orderId={}", order.orderId, e)
        skipped += order.orderId
    }
```

한 주문 처리 실패가 나머지 주문 처리를 중단시키지 않는다.
이것은 **부분 실패를 허용하는 배치 처리**의 표준 패턴이다.
실패한 주문은 `skippedOrders`에 기록되어 다음 Airflow 실행 시 재처리된다.

---

## 7. SagaTimeoutHandler — 트랜잭션 격리와 Terminal-First 패턴

### 왜 SagaTimeoutService와 SagaTimeoutHandler를 분리했는가

Spring의 `@Transactional`은 **AOP 프록시**로 동작한다.
같은 Bean 내부의 메서드 호출은 프록시를 거치지 않아 트랜잭션이 적용되지 않는다 (Self-invocation 문제).

```
[Self-invocation 문제]
SagaTimeoutService.processStuckOrders()
  ↓ 내부 호출 (프록시 우회)
  handleOrder(order)  ← @Transactional이지만 실제론 적용 안 됨!
```

별도 Bean(`SagaTimeoutHandler`)으로 분리하면:
```
SagaTimeoutService.processStuckOrders()
  ↓ 다른 Bean 호출 (프록시 통과)
  SagaTimeoutHandler.handle(order)  ← @Transactional 정상 적용 ✅
```

또한 주문마다 **독립적인 트랜잭션**을 갖게 되어, 한 주문의 실패가 다른 주문에 영향을 주지 않는다.

### Terminal-First 패턴

```kotlin
// SagaTimeoutHandler.kt
@Transactional
suspend fun handle(order: Orders): TimeoutOutcome {
    // Step 1: 먼저 terminal 상태로 저장
    orderRepository.save(order.copy(
        status = OrderStatus.CANCELLED,
        sagaState = sagaState?.copy(currentStep = SagaStep.FAILED, failureReason = "Saga timeout")
    ))

    // Step 2: 그 다음 보상 outbox 이벤트 발행
    outboxRepository.save(buildInventoryReleaseEvent(order))
}
```

**왜 terminal 상태 저장을 먼저 하는가?**

만약 순서가 반대라면:
```
1. 보상 outbox 이벤트 발행
2. (이 사이에 원본 서비스의 late reply 도착)
3. Orchestrator: 주문 상태가 아직 PENDING → late reply를 처리해버림
4. terminal 상태로 저장 → 이중 처리 발생
```

Terminal-First로 하면:
```
1. CANCELLED로 저장 (version 증가)
2. (이 사이에 late reply 도착)
3. Orchestrator: isTerminated() → true → late reply 무시 ✅
4. 보상 outbox 이벤트 발행
```

**두 저장이 같은 트랜잭션인 이유:**
- 1번(상태 저장)만 성공하고 2번(outbox 발행)이 실패하면 고객 환불이 안 됨
- 반드시 원자적으로 처리되어야 함

---

## 8. 상태별 Best-Effort 보상 전략

### "Best-Effort"의 의미

보상 트랜잭션이 실패할 수 있다는 것을 전제하고, **최선을 다해 시도**하는 방식.
완전한 보상(ACID)이 불가능한 분산 환경에서의 현실적인 접근이다.

### 생성 Saga 타임아웃

```
PENDING 상태에서 타임아웃 발생 시:

case 1. 아무 단계도 안 됨 (inventoryReserved=false, paymentProcessed=false)
  → CANCELLED. 보상 불필요

case 2. 재고만 예약됨 (inventoryReserved=true)
  → CANCELLED + 재고 해제 Outbox 발행

case 3. 결제까지 됨 (paymentProcessed=true)
  → CANCELLED + 환불 Outbox + 재고 해제 Outbox 발행

case 4. COMPENSATING 단계에서 멈춤 (보상 자체가 데드락)
  → SKIPPED → 수동 개입 필요
```

**고객 돈(결제)이 걸린 순간부터 반드시 환불을 시도**한다.

### 취소 Saga 타임아웃

```
CANCELLING 상태에서 타임아웃:

case 1. 아무것도 안 됨 → CANCELLATION_FAILED (수동 개입)
case 2. 배송취소 O, 환불 X → CANCELLATION_FAILED + 환불 Outbox ← 고객 돈 보호
case 3. 환불 O, 재고복구 X → CANCELLATION_FAILED + 재고 해제 Outbox
```

배송이 이미 취소됐는데 환불이 안 됐다면 고객이 상품도 없고 돈도 없는 최악의 상태다.
이 케이스를 최우선으로 처리한다.

### 반품 Saga 타임아웃

```
RETURN_REQUESTED / RETURN_IN_PROGRESS 에서 타임아웃:

case 1. 상품 수령 전 → RETURN_FAILED (수동 개입 - 고객에게 상품 돌려줘야 함)
case 2. 상품 수령 O, 환불 X → RETURN_FAILED + 환불 Outbox ← 고객 돈 보호
case 3. 환불 O, 재고복구 X → RETURN_FAILED + 재고 해제 Outbox
```

**공통 원칙:** 고객의 돈과 상품이 둘 다 없어지는 상황을 최우선으로 방지한다.

### SKIPPED = 수동 개입 신호

`TimeoutOutcome.SKIPPED`는 자동 복구가 불가능한 상태를 의미한다.
이 주문 ID들은 Airflow의 `analyze_timeout_result` 태스크에서 `RuntimeError`를 발생시켜
DAG를 실패 처리하고 이메일 알림을 트리거한다.

---

## 9. PREPARING 상태 취소 — 알려진 경쟁 조건과 설계 결정

### 허용된 경쟁 조건

```kotlin
// OrderService.kt
val CANCELLABLE_STATUSES = setOf(OrderStatus.PAYMENT_COMPLETED, OrderStatus.PREPARING)
```

`PREPARING` 상태에서의 취소를 허용한다.
이 상태는 delivery-service가 출고 준비 중인 시점이므로 이론상 취소와 출고가 동시에 진행될 수 있다.

**시나리오:**
```
delivery-service: 이미 출고 처리 시작 (PREPARING 상태)
        ↓  (동시에)
고객: 취소 요청 → order-service가 CANCELLING으로 변경
        ↓
order-service: delivery-cancel command 발행
        ↓
delivery-service: "이미 출고됨, 취소 불가" → 실패 Reply
        ↓
order-service: CANCELLATION_FAILED
```

### 왜 허용했는가

1. **고객 경험**: PAYMENT_COMPLETED → PREPARING 전환 사이(수 초)에 취소하고 싶은 고객이 실제로 있다.
   이 창(window)을 막으면 불필요한 마찰이 생긴다.

2. **CANCELLATION_FAILED가 허용 가능한 결과**: 배송이 이미 시작됐다면 취소가 실패하는 것은 **정상 비즈니스 결과**다.
   고객에게 "이미 출고되어 취소 불가"를 안내하면 된다.

3. **보호 장치가 충분함**:
   - `@Version` 낙관적 잠금으로 동시 취소 요청 중 하나만 성공
   - `CANCELLATION_FAILED` 시 `SagaTimeoutHandler`가 환불·재고 복구 보장
   - delivery-service는 멱등적 cancel 처리

### CANCELLATION_FAILED가 최종 상태인 이유

`CANCELLATION_FAILED`를 에러로 취급하지 않고 **정상적인 terminal 상태**로 설계한 이유:
- 이 상태를 Airflow 타임아웃 스캔 대상에서 제외 (이미 처리 완료)
- `isTerminated()` 로직에 포함되어 subsequent late reply 무시
- 운영팀이 고객 CS 처리 후 수동으로 환불 여부 결정 가능

---

## 10. Airflow vs Spring @Scheduled — 스케줄러 선택 이유

### Spring @Scheduled의 한계

```kotlin
// Spring @Scheduled 방식
@Scheduled(fixedDelay = 30 * 60 * 1000L)
suspend fun detectStuckSagas() { ... }
```

이 방식의 문제:
1. **멀티 인스턴스 문제**: order-service 인스턴스가 3개라면 같은 로직이 3번 실행됨
2. **가시성 없음**: 실행 여부, 실패 여부를 로그 없이 알 수 없음
3. **복잡한 의존성 관리**: 헬스 체크 → 처리 → 분석 순서를 코드로만 표현해야 함
4. **재시도 설정 어려움**: 실패 시 재시도 로직을 직접 구현해야 함
5. **모니터링 부재**: 실행 이력, 실패율, 처리 시간 통계가 없음

### Airflow 선택 이유

```
[Airflow DAG 실행 흐름]
order_service_health_check
    ↓ (성공 시에만)
process_stuck_sagas  (POST /internal/saga/timeout/process)
    ↓
analyze_result  (응답 파싱, 이상 탐지, 알림)
```

| 기능 | @Scheduled | Airflow |
|------|-----------|---------|
| 단일 실행 보장 | 별도 분산 락 필요 | `max_active_runs=1` 한 줄 |
| 실행 이력 조회 | 로그 파일 | 웹 UI에서 그래프로 |
| 실패 시 재시도 | 직접 구현 | `retries=3, retry_exponential_backoff=True` |
| 헬스 체크 후 실행 | 직접 구현 | `HttpSensor` + 태스크 의존성 |
| 알림(이메일/Slack) | 직접 구현 | `email_on_failure=True`, `on_failure_callback` |
| 백필 방지 | 해당 없음 | `catchup=False` |

### Kotlin + Python 혼용 구조

```
[아키텍처 분리]
Spring Boot (Kotlin)   ← 비즈니스 로직 (도메인, Saga, DB 트랜잭션)
     ↕  REST API
Airflow (Python)       ← 스케줄링, 재시도, 모니터링, 알림
```

Spring Boot는 **"무엇을 할 것인가"** (비즈니스 로직)를 담당하고,
Airflow는 **"언제, 어떻게 실행할 것인가"** (운영 관심사)를 담당한다.

이는 단일 책임 원칙(SRP)을 서비스 수준에서 적용한 것이다.

### Airflow DAG 설계 결정

```python
# infrastructure/airflow/dags/saga_timeout_dag.py
with DAG(
    dag_id="saga_timeout_processor",
    schedule=timedelta(minutes=30),  # 생성 Saga 임계값(30분)과 동일
    max_active_runs=1,               # 동시 실행 방지
    catchup=False,                   # 과거 기간 백필 방지
    default_args={
        "retries": 3,
        "retry_exponential_backoff": True,  # 1→2→4분 지수 백오프
        "max_retry_delay": timedelta(minutes=10),
    }
) as dag:
    health_check >> process_timeouts >> analyze_result
```

**schedule = 30분**: 생성 Saga 타임아웃 임계값과 동일. 즉, 타임아웃 직후 다음 실행에서 탐지 가능.

**analyze_result 태스크의 3단계 판정:**
```python
# 정상:  processedCount == 0  → 모든 Saga 정상
# 경고:  processedCount > 0   → 멈춘 Saga 발생 (근본 원인 조사 필요)
# 심각:  skippedOrders != []  → 자동 복구 불가 → RuntimeError → 이메일 알림
```

---

## 11. InternalSagaController — 내부 전용 엔드포인트 보안 설계

### 왜 별도 컨트롤러가 필요한가

Airflow가 호출하는 엔드포인트는 **외부 사용자가 호출하면 안 된다**.
임의로 호출되면 멀쩡한 주문이 강제 취소될 수 있다.

### 보안 계층 설계

```
외부 클라이언트
    ↓
Spring Cloud Gateway (8080)  ← /internal/** 경로 차단
    ↓
order-service (9001)          ← 직접 접근은 VPN/클러스터 내부에서만
    ↓
InternalSagaController        ← POST /internal/saga/timeout/process
```

Gateway 레벨에서 `/internal/**/` 경로를 라우팅 목록에서 제외하여
외부 트래픽이 이 엔드포인트에 도달하지 못하게 한다.

Airflow는 **클러스터 내부**에서 직접 9001 포트로 order-service를 호출한다.

### 멱등성 보장

Airflow가 동일 DAG를 여러 번 실행해도 안전하도록 멱등성을 보장한다.

```kotlin
// SagaTimeoutService.kt
// 쿼리: findByStatusInAndUpdatedAtBefore(TIMEOUT_STATUSES, minThreshold)
// TIMEOUT_STATUSES = [PENDING, CANCELLING, RETURN_REQUESTED, RETURN_IN_PROGRESS]
// 이미 CANCELLED, CANCELLATION_FAILED, RETURN_FAILED 등 terminal 상태는 쿼리에서 제외됨
```

한 번 처리된 주문은 terminal 상태가 되므로 다음 실행에서는 쿼리 결과에 포함되지 않는다.

---

## 전체 아키텍처 흐름 요약

```
[정상 흐름]
주문 생성 → PENDING
    → inventory-command (Outbox + CDC)
    → InventoryReply (성공) → PAYMENT_PROCESS step
    → payment-command
    → PaymentReply (성공) → PAYMENT_COMPLETED → DELIVERY_CREATE step
    → delivery-command
    → DeliveryReply (성공) → PREPARING (Saga 완료)

[실패/타임아웃 흐름]
주문 생성 → PENDING
    → (Reply가 오지 않음)
    → 30분 후 Airflow가 탐지
    → SagaTimeoutHandler:
        1. CANCELLED로 저장 (terminal-first)
        2. 진행된 단계에 따라 보상 Outbox 발행
    → Airflow analyze_result:
        processedCount > 0 → 경고 로그
        skippedOrders != [] → RuntimeError → 이메일 알림

[동시성 충돌 흐름]
Consumer A, B가 동시에 같은 Reply 처리 시도
    → OptimisticLockingFailureException 발생 (하나가 먼저 저장됨)
    → shouldAck = false → Kafka가 재시도
    → 재시도 시 이미 terminal → isTerminated() = true → skip
```

---

## 관련 파일

| 파일 | 역할 |
|------|------|
| `saga/OrderSagaOrchestrator.kt` | Saga Reply 처리, shouldAck 패턴 |
| `config/KafkaConfig.kt` | JitterExponentialBackOff, DLT 설정 |
| `service/SagaTimeoutService.kt` | 타임아웃 탐지, 임계값 필터링 |
| `saga/SagaTimeoutHandler.kt` | 단일 주문 @Transactional 보상 처리 |
| `controller/InternalSagaController.kt` | Airflow REST 엔드포인트 |
| `dto/TimeoutProcessResult.kt` | 처리 결과 DTO |
| `repository/OrderRepository.kt` | `findByStatusInAndUpdatedAtBefore` 쿼리 |
| `service/OrderService.kt` | PREPARING 취소 허용 설계 결정 |
| `infrastructure/airflow/dags/saga_timeout_dag.py` | Airflow DAG 정의 |
