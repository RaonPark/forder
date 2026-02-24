# Forder 개발 세션 정리

## 1. 남은 작업 목록

### 🔴 Critical (구현 버그)

#### 보상 Reply 라우팅 문제
현재 `onInventoryReply`가 RESERVE 응답과 RELEASE(보상) 응답을 구분하지 못함.
`sagaState.currentStep`으로 분기 처리 필요.

```kotlin
@KafkaListener(topics = [INVENTORY_REPLY_TOPIC])
suspend fun onInventoryReply(reply: InventoryReply) {
    val order = orderRepository.findById(reply.sagaId) ?: return
    
    when (order.sagaState?.currentStep) {
        SagaStep.INVENTORY_RESERVE -> {
            // 정방향: 재고 예약 결과
            if (reply.success) proceedToPayment(order)
            else failSaga(order, reply.failureReason ?: "재고 예약 실패")
        }
        SagaStep.COMPENSATING -> {
            // 보상: 재고 해제 완료 → 최종 실패 처리
            failSaga(order, order.sagaState?.failureReason ?: "Saga 보상 완료")
        }
        else -> log.warn("Unexpected step: ${order.sagaState?.currentStep}")
    }
}
```
`onPaymentReply`도 동일하게 수정 필요.

---

### 🟡 필수 구현

#### 각 서비스 build.gradle.kts
inventory-service, payment-service, delivery-service에 common 프로토 클래스 사용을 위한 의존성 추가 필요.
```kotlin
repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}
dependencies {
    implementation(project(":common"))
    implementation("io.confluent:kafka-protobuf-serializer:7.8.0")
    implementation("com.google.protobuf:protobuf-kotlin:3.25.3")
}
```

#### 각 서비스 KafkaConfig
inventory-service, payment-service, delivery-service 각각 KafkaConfig 필요.
(order-service의 KafkaConfig 참고)

#### 멱등성 처리
Kafka 재전달 시 중복 처리 방지. 각 CommandHandler에서 sagaId 기준 중복 확인.
```kotlin
// 예: InventoryCommandHandler
if (inventoryRepository.existsBySagaId(command.sagaId)) {
    return replyWithPreviousResult(command.sagaId)
}
```

#### Saga 타임아웃 처리
일정 시간 PENDING 상태인 주문 감지 → 강제 보상.
Spring Batch 또는 `@Scheduled`로 주기적 체크.

#### query-service
- `orders-view` Elasticsearch 인덱스 구현
- `GET /v1/orders/{orderId}` 조회 엔드포인트 구현

#### PaymentCommandHandler 실제 로직 연동
현재 TODO로 표시된 `paymentService.createPayment()` 실제 구현 연결.

#### DeliveryCommandHandler 실제 로직 연동
현재 TODO로 표시된 `deliveryService.createDelivery()` 실제 구현 연결.

---

### 🟢 추후 개선

- **Outbox 패턴**: `orderRepository.save()` + `kafkaTemplate.send()` 원자성 보장
- **DLQ 모니터링**: `*-dlt` 토픽 알림 및 재처리 전략
- **테스트**: OrderService 단위 테스트, Saga 통합 테스트 (TestContainers)
- **Proto 파일**: `OrderSagaState`, `SagaStep`도 proto로 관리 고려
- **Kafka 토픽 생성**: infrastructure에 Command/Reply 토픽 설정 추가

---

## 2. 이론 정리

### Lazy Autowiring

Spring의 기본 동작은 애플리케이션 시작 시 모든 빈을 즉시 생성(Eager)한다.
Lazy는 처음 사용될 때 빈을 생성한다.

| | Eager (기본) | Lazy |
|--|--|--|
| 빈 생성 시점 | 앱 시작 시 | 처음 사용 시 |
| 시작 시간 | 느림 | 빠름 |
| 첫 요청 응답 | 빠름 | 느림 (초기화 비용) |
| 순환 의존성 감지 | 시작 시점 (안전) | 런타임 (위험) |

```kotlin
// 방법 1: @Lazy
@Service
class MyService(@Lazy private val heavyBean: HeavyBean)

// 방법 2: ObjectProvider (Spring 권장)
@Service
class MyService(private val provider: ObjectProvider<HeavyBean>) {
    fun doSomething() = provider.getObject().execute()
}
```

---

### CoroutineCrudRepository vs CoroutineSortingRepository

Spring Data 3.0+ 에서 두 인터페이스가 분리됨.

```
Spring Data 2.x: CoroutineSortingRepository → CoroutineCrudRepository (상속)
Spring Data 3.x: CoroutineSortingRepository    CoroutineCrudRepository (분리)
```

`save()` 등 CRUD + 정렬 조회 모두 필요하면 두 인터페이스 동시 상속.

```kotlin
interface PaymentRepository
    : CoroutineCrudRepository<Payment, String>,
      CoroutineSortingRepository<Payment, String>
```

---

### Saga 패턴: Choreography vs Orchestration

#### Choreography
각 서비스가 이벤트를 보고 자율적으로 반응.

```
OrderService  →  order-created (브로드캐스트)
                     ↓           ↓           ↓
                Inventory    Payment    Delivery  (각자 반응)
```

- 장점: 서비스 간 결합도 낮음
- 단점: 전체 흐름 파악 어려움, 3단계 이상 복잡해짐

#### Orchestration (이 프로젝트)
중앙 오케스트레이터가 각 서비스에 명령을 내리고 결과를 받아 다음 단계 결정.

```
OrderSagaOrchestrator
    → inventory-command  →  InventoryService
    ← inventory-reply    ←
    → payment-command    →  PaymentService
    ← payment-reply      ←
    → delivery-command   →  DeliveryService
    ← delivery-reply     ←
```

- 장점: 흐름 파악 쉬움, 복잡한 보상 트랜잭션 관리 용이
- 단점: 오케스트레이터가 병목 가능성

#### Orchestration with Kafka (Command/Reply Pattern)
Orchestration이라도 WebClient(동기) 대신 Kafka(비동기)를 사용할 수 있다.

| | WebClient (동기) | Kafka (비동기) |
|--|--|--|
| 결합도 | 서비스 직접 호출 (높음) | 토픽 통해 간접 호출 (낮음) |
| 가용성 | 대상 서비스 다운 시 즉시 실패 | 메시지 보관으로 내결함성 |
| 인프라 활용 | Kafka 낭비 | Kafka + DLQ 그대로 활용 |

---

### Saga Orchestrator 위치

**Gateway에 두면 안 되는 이유:**
- Gateway는 인프라 레이어 (라우팅, 인증, 서킷브레이커)
- 비즈니스 로직(도메인 지식)이 인프라 레이어에 침투
- Gateway가 Stateful해져 확장성 저하
- Gateway 장애 = 전체 Saga 중단

**올바른 위치:** 해당 도메인 서비스 내부 (order-service)
DDD 관점에서 Order가 주문 프로세스 전체의 Aggregate Root.

---

### Kafka Manual ACK vs Auto Commit

Auto Commit은 메시지 수신 즉시 오프셋을 커밋한다.
비즈니스 로직 처리 중 실패해도 재처리 불가.

Manual ACK는 비즈니스 로직 완료 후 `ack.acknowledge()` 호출 시 커밋.
실패 시 Kafka가 재전달 → DLQ로 이동.

```kotlin
@KafkaListener(topics = ["inventory-reply"])
suspend fun onInventoryReply(reply: InventoryReply, ack: Acknowledgment) {
    orderRepository.save(...)      // DB 작업
    kafkaTemplate.send(...)        // 다음 커맨드 발행
    ack.acknowledge()              // 모든 작업 성공 후 커밋
}
```

**주의:** Manual ACK 사용 시 재전달로 인한 중복 처리 가능 → 멱등성 설계 필수.

---

### Protobuf + Schema Registry

#### SPECIFIC_PROTOBUF_VALUE_TYPE vs DERIVE_TYPE_CONFIG

| | SPECIFIC_PROTOBUF_VALUE_TYPE | DERIVE_TYPE_CONFIG |
|--|--|--|
| 동작 방식 | 개발자가 타입 명시 | Schema Registry에서 자동 추론 |
| 안전성 | 클래스 불일치 문제 없음 | 공유 모듈 전략 필수 |
| 유연성 | 타입별 ConsumerFactory 필요 | 단일 Factory로 다중 타입 처리 |

#### 공유 모듈 전략 (Shared Module)
여러 서비스가 각자 `.proto` 파일을 복사해 클래스를 생성하면,
패키지명이 같아도 JVM이 다른 ClassLoader로 로드한 별개 클래스로 취급 → ClassCastException.

**해결책:** 단일 `common` 모듈에 `.proto` 파일을 모아두고, 생성된 JAR를 모든 서비스가 의존.

```
common 모듈 (단일 proto 생성 JAR)
    ↓         ↓         ↓         ↓
order   inventory  payment  delivery  ← 동일한 클래스 참조
```

#### proto3 필드 타입 주의사항
- `BigDecimal` 타입 없음 → `string`으로 전송 후 파싱
- `optional` 필드는 `has*()` 메서드로 존재 여부 확인
- enum 첫 번째 값은 반드시 `0` (UNSPECIFIED) → 미설정 기본값 안전 처리

---

### CQRS

Command(쓰기)와 Query(읽기) 모델을 분리.

```
Command Side                    Query Side
────────────                    ──────────
POST /v1/orders                 GET /v1/orders/{orderId}
    ↓                               ↑
order-service                   query-service
(MongoDB 저장)                  (Elasticsearch 조회)
    ↓                               ↑
MongoDB → Debezium CDC → Kafka → Kafka Streams → Elasticsearch
```

**Command 응답에 status 포함 불필요:**
- 주문 생성 응답은 항상 `PENDING` → 의미 없는 정보
- 상태 조회는 query-service에서 담당
- Command 응답에는 `orderId`만 포함하면 충분

**클라이언트 상태 추적 방법:**
1. 폴링: `GET /v1/orders/{orderId}` 주기적 조회
2. SSE (Server-Sent Events): query-service가 상태 변경 시 push

---

### DLQ (Dead Letter Queue)

메시지 처리 실패 시 재시도 후 최종 실패하면 DLQ 토픽으로 발행.

```
메시지 처리 실패
    → N회 재시도 (FixedBackOff)
    → 최종 실패 시 {topic}-dlt 토픽으로 발행
    → 운영자 모니터링 후 수동 재처리 or 알림
```

```kotlin
val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate)
val errorHandler = DefaultErrorHandler(recoverer, FixedBackOff(1_000L, 3L))
factory.setCommonErrorHandler(errorHandler)
```

---

### Idempotency (멱등성)

같은 요청을 여러 번 처리해도 결과가 동일하도록 설계.
Kafka는 네트워크 오류, 재시작 등으로 메시지를 재전달할 수 있기 때문에 필수.

```kotlin
// sagaId로 이미 처리된 커맨드인지 확인
fun handle(command: InventoryCommand) {
    if (alreadyProcessed(command.sagaId)) {
        return sendCachedReply(command.sagaId)
    }
    // 실제 처리
}
```

---

### Outbox 패턴

`save()` 성공 후 `kafkaTemplate.send()` 실패 시 데이터 불일치 발생.

```
orderRepository.save()   ✅
kafkaTemplate.send()     💥 실패
→ DB에는 저장됐지만 Kafka에 이벤트 없음 → Saga 시작 안 됨
```

**해결:** MongoDB에 Outbox 컬렉션에 이벤트 저장 → Debezium CDC가 Kafka로 발행.
쓰기와 이벤트 발행이 단일 MongoDB 트랜잭션으로 묶임.
