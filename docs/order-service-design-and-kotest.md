# OrderService 도메인 설계 결정과 Kotest 학습 가이드

---

## Part 1: OrderService 도메인 설계 결정

### 1. Outbox 패턴 — Double-Save 버그 수정

**기존 코드의 문제:**
```kotlin
// ❌ 잘못된 구현
suspend fun createOrder(request: CreateOrderRequest): OrderResponse {
    val order = orderRepository.save(Orders(..., status = PENDING))  // 1번 저장
    sagaOrchestrator.startSaga(order)  // 내부에서 2번째 저장 (sagaState 추가)
    return OrderResponse.from(order)
}
```

두 번 저장하면:
- 첫 번째 `save` 후 서버가 죽으면 `sagaState` 없는 PENDING 주문이 DB에 남음
- Kafka CDC가 불완전한 이벤트를 발행할 수 있음
- 트랜잭션이 두 개로 분리되어 원자성 보장 불가

**올바른 구현:**
```kotlin
// ✅ 단일 트랜잭션
@Transactional
suspend fun createOrder(request: CreateOrderRequest): OrderResponse {
    val order = Orders(sagaState = OrderSagaState(step = INVENTORY_RESERVE), ...)
    orderRepository.save(order)      // Order + sagaState 함께
    outboxRepository.save(OutboxEvent.of(...))  // 같은 트랜잭션
    return OrderResponse.from(order)
}
```

핵심 원칙: **"Order 저장"과 "Kafka 발행 트리거(Outbox 저장)"는 반드시 같은 MongoDB 트랜잭션 안에 있어야 한다.** Debezium CDC가 Outbox 컬렉션 변경을 감지해서 Kafka에 발행하기 때문에, 트랜잭션이 롤백되면 Outbox도 함께 롤백되어 중복 발행이 원천 차단된다.

---

### 2. 상태 불변식 가드 (Domain Invariants)

```kotlin
private val CANCELLABLE_STATUSES = setOf(
    OrderStatus.PAYMENT_COMPLETED,
    OrderStatus.PREPARING
)

fun cancelOrder(orderId: String, request: CancelOrderRequest): OrderResponse {
    val order = orderRepository.findById(orderId) ?: throw NoSuchElementException(...)
    check(order.status in CANCELLABLE_STATUSES) {
        "취소 불가 상태: ${order.status}"
    }
    ...
}
```

**왜 `check`를 쓰는가?**
- `check()`는 객체의 상태 불변식 위반 시 `IllegalStateException`을 던짐
- `require()`는 파라미터 유효성 검증 시 `IllegalArgumentException`을 던짐
- 의미론적으로 구분: "이 상태에서는 취소할 수 없다"는 상태 불변식이므로 `check`가 적합

**왜 취소 가능 상태를 `PAYMENT_COMPLETED`와 `PREPARING`으로만 제한하는가?**
- `PENDING`: Saga 진행 중. 중간에 취소하면 보상 체인이 꼬임
- `SHIPPING` / `DELIVERED`: 이미 물리적으로 진행됨 → 반품 프로세스로 유도
- `CANCELLING`: 이미 취소 중 → 중복 요청 방지

---

### 3. Saga 흐름 판별자 (Flow Discriminator)

세 가지 Saga(생성/취소/반품)는 동일한 Kafka reply topic을 공유한다.
`inventory-reply`를 예로 들면:
- 생성 Saga: 재고 예약 결과
- 취소 Saga: 재고 복구 결과
- 반품 Saga: 재고 복구 결과

**판별 기준으로 `order.status`를 사용:**
```kotlin
when (order.status) {
    OrderStatus.CANCELLING         -> handleCancellationInventoryRestore(order, reply)
    OrderStatus.RETURN_IN_PROGRESS -> handleReturnInventoryRestore(order, reply)
    else -> when (order.sagaState?.currentStep) {  // 생성 Saga
        SagaStep.INVENTORY_RESERVE -> handleCreationInventoryReserve(order, reply)
        SagaStep.COMPENSATING      -> handleCreationCompensationInventoryRelease(order, reply)
        ...
    }
}
```

`sagaId`(= orderId)만으로는 어느 흐름인지 알 수 없다. 반드시 `order.status`를 DB에서 조회해 흐름을 구분해야 한다.

---

### 4. 멱등성 (Idempotency)

Kafka는 at-least-once delivery를 보장한다. 즉 같은 메시지가 두 번 올 수 있다.

```kotlin
private suspend fun handleCreationInventoryReserve(order: Orders, reply: InventoryReply) {
    if (order.sagaState?.inventoryReserved == true) return  // 이미 처리됨
    ...
}

private suspend fun handleCancellationDeliveryCancel(order: Orders, reply: DeliveryReply) {
    if (order.cancellationStatus?.deliveryCancelled == true) return  // 이미 처리됨
    ...
}
```

각 Saga 스텝에 boolean 플래그(`inventoryReserved`, `deliveryCancelled`, `pickupScheduled` 등)를 두어, 중복 reply가 와도 한 번만 처리되도록 보장한다. `@Version`(낙관적 락)과 함께 쓰면 동시성 상황에서도 안전하다.

---

### 5. 종료 상태 보호 (Late Reply Protection)

네트워크 지연으로 수십 초 뒤 도착한 reply를 무시하지 않으면 이미 완료된 Saga가 다시 처리될 수 있다.

```kotlin
private val TERMINAL_STATUSES = setOf(
    OrderStatus.CANCELLED,
    OrderStatus.CANCELLATION_FAILED,
    OrderStatus.RETURN_COMPLETED,
    OrderStatus.RETURN_FAILED
)

private fun isTerminated(order: Orders): Boolean =
    order.status in TERMINAL_STATUSES ||
    order.sagaState?.currentStep in setOf(SagaStep.COMPLETED, SagaStep.FAILED)
```

종료 상태이면 비즈니스 로직 없이 바로 `return` — `finally`의 `ack.acknowledge()`는 항상 실행된다.

---

### 6. 보상 체인 설계 (Compensation Chain)

생성 Saga 실패 시 보상 순서:
```
배송 실패 → 결제 환불 명령 발행
환불 성공/실패 무관 → 재고 해제 명령 발행 (Best-Effort)
재고 해제 완료 → CANCELLED
```

**왜 환불 실패해도 재고 해제로 진행하는가?**
환불 실패를 이유로 재고를 계속 잡아두면 다른 고객이 해당 재고를 살 수 없게 된다. 환불 실패는 별도 운영 채널(Customer Service)에서 처리하고, 시스템은 재고 복구를 우선한다.

```kotlin
private suspend fun handleCreationCompensationPaymentRefund(order: Orders, reply: PaymentReply) {
    // 환불 성공/실패 불문하고 재고 해제 진행 (Best-Effort 보상)
    if (!reply.success) {
        log.warn("[CreationSaga] Payment refund failed during compensation - sagaId={}", reply.sagaId)
    }
    compensateCreationInventory(order, order.sagaState?.failureReason ?: "보상 진행 중")
}
```

---

### 7. failCancellationSaga의 null 안전성 — `?.let` 사용

```kotlin
// ❌ 중간 변수로 null 전파 위험
val currentStatus = order.cancellationStatus
cancellationStatus = currentStatus?.copy(...)  // currentStatus가 null이면 null 반환 → 상태 유실

// ✅ let으로 스코프 내 null 안전 처리
cancellationStatus = order.cancellationStatus?.let { cs ->
    cs.copy(...)  // cs는 non-null 보장
}
```

`?.let { }` 패턴: null이면 람다 전체를 실행하지 않고 null 반환. 중간 변수 없이 인라인으로 null-safe 변환 표현이 가능하다.

더 강한 방어가 필요하면 `requireNotNull(order.cancellationStatus) { "..." }` 로 early fail.

---

### 8. 책임 분리 — OrderService vs OrderSagaOrchestrator

| 관심사 | OrderService | OrderSagaOrchestrator |
|---|---|---|
| 주문 생성/조회 | ✅ | ❌ |
| 도메인 상태 불변식 검사 | ✅ | ❌ |
| Saga 첫 명령 발행 (Outbox) | ✅ | ❌ |
| Kafka reply 처리 | ❌ | ✅ |
| Saga 스텝 전진/보상 | ❌ | ✅ |

OrderService는 사용자 요청(REST)을 처리하는 애플리케이션 계층, OrderSagaOrchestrator는 Kafka 이벤트를 처리하는 인프라 계층으로 분리한다.

---

### 9. `finally`를 활용한 ack 처리 — DLT 전략

```kotlin
// ❌ 기존: ack가 각 분기에 흩어짐 + findOrder에 ack를 넘기는 반응형 설계
private suspend fun findOrder(sagaId: String, ack: Acknowledgment): Orders? {
    val order = orderRepository.findById(sagaId)
    if (order == null) ack.acknowledge()  // SRP 위반: 조회 메서드가 ack 책임까지 가짐
    return order
}

// ✅ 개선: finally로 ack 보장, findOrder는 조회만
suspend fun onInventoryReply(reply: InventoryReply, ack: Acknowledgment) {
    try {
        val order = findOrder(reply.sagaId) ?: return  // return해도 finally 실행
        ...
    } finally {
        ack.acknowledge()  // 성공/실패/예외/return 어떤 경로든 보장
    }
}
```

DLT(Dead Letter Topic) 전략에서는 예외가 발생해도 메시지를 ack 처리하고, Spring Kafka의 에러 핸들러가 DLT로 라우팅한다. `finally`는 이 패턴에 자연스럽게 맞는다.

`findOrder`에 `ack`를 넘겼던 설계가 잘못된 이유:
1. **단일 책임 원칙(SRP) 위반**: 조회 메서드가 Kafka ack라는 인프라 관심사를 떠안음
2. **부수 효과 은닉**: 시그니처만 보면 단순 조회처럼 보이지만 ack라는 외부 효과 발생
3. **테스트 복잡성 증가**: findOrder 단위 테스트 시 항상 ack mock 필요

---

## Part 2: Kotest 학습 가이드

### 1. Kotest Spec 스타일 종류

Kotest는 다양한 테스트 스타일을 제공한다:

| 스타일 | 특징 | 적합한 경우 |
|---|---|---|
| `FunSpec` | `test("name") { }` | 간단한 함수 단위 테스트 |
| `StringSpec` | `"name" { }` | 가장 간결 |
| `BehaviorSpec` | `given/when/then` | BDD, 시나리오 중심 |
| `DescribeSpec` | `describe/context/it` | RSpec 스타일 |
| `ShouldSpec` | `should("name") { }` | 명세 중심 |
| `AnnotationSpec` | `@Test` | JUnit 마이그레이션 |

---

### 2. BehaviorSpec — BDD 스타일

```kotlin
class OrderServiceTest : BehaviorSpec({
    given("cancelOrder") {           // 테스트 대상 / 시나리오 컨텍스트
        `when`("PAYMENT_COMPLETED 상태") {  // 조건
            // 여기서 setup + action
            val order = sampleOrder(status = PAYMENT_COMPLETED)
            coEvery { ... } returns order
            val result = service.cancelOrder("order-001", request)

            then("CANCELLING으로 변경된다") {  // 검증
                result.status shouldBe CANCELLING
            }
        }
    }
})
```

**BDD(Behaviour-Driven Development)의 장점:**
- `given`(주어진 상황) → `when`(행동) → `then`(결과) 구조로 테스트 의도가 명확
- 비개발자도 읽을 수 있는 테스트 명세가 됨
- 하나의 `when`에 여러 `then`을 두어 같은 시나리오의 여러 측면 검증 가능

---

### 3. IsolationMode — 테스트 격리 수준

```kotlin
class MySpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerTest
    ...
})
```

| 모드 | 동작 | 사용 시점 |
|---|---|---|
| `SingleInstance` (기본) | Spec 인스턴스 하나를 모든 테스트가 공유 | 가볍고 빠른 테스트 |
| `InstancePerTest` | 각 테스트 노드마다 Spec 새 인스턴스 생성 | Kotest 코루틴 상태 격리 필요 시 |
| `InstancePerLeaf` | 리프 테스트(`then`)마다 Spec 새 인스턴스 | Kotest 6.x에서 일부 필터 이슈 있음 |

`InstancePerTest`를 쓰는 이유:
- Kotest는 코루틴 기반 → `runTest` 실행 후 코루틴 컨텍스트 상태가 다음 테스트에 영향 가능
- Spec 재인스턴스화로 `val orderRepository = mockk<OrderRepository>()` 같은 선언이 매 테스트마다 새로 초기화됨

---

### 4. Lifecycle Hooks — 실행 순서

```kotlin
class MySpec : BehaviorSpec({
    beforeSpec { /* 전체 Spec 시작 전 1회 */ }
    afterSpec  { /* 전체 Spec 종료 후 1회 */ }
    beforeEach { /* 각 테스트 전 */ }
    afterEach  { /* 각 테스트 후 */ }
})
```

**BehaviorSpec에서의 실행 순서:**

```
[when 블록 setup 코드 실행]
    ↓
[then 블록 body 실행]  ← beforeEach는 when setup보다 먼저 실행됨 (주의!)
    ↓
[afterEach 실행]
```

**왜 `afterEach { clearAllMocks() }`를 쓰는가?**

`beforeEach { clearAllMocks() }` 를 쓰면 `when` 블록의 액션(orchestrator 호출 등)이 실행된 뒤에 `clearAllMocks()`가 동작해 호출 기록이 지워진다. 이렇게 되면 `then` 블록에서 `coVerify`로 검증할 것이 없어진다.

`afterEach`는 `then` 실행 후에 정리하므로 검증이 끝난 뒤 mock 상태를 초기화해 다음 테스트에 영향을 주지 않는다.

---

### 5. MockK 핵심 개념

#### coEvery / coVerify — suspend 함수용

```kotlin
// stub: findById가 호출되면 order를 반환
coEvery { orderRepository.findById("order-001") } returns order

// 실행 후 검증
coVerify(exactly = 1) { orderRepository.save(any()) }
coVerify(exactly = 0) { outboxRepository.save(any()) }  // 호출 안 됨을 검증
coVerify(atLeast = 1) { ack.acknowledge() }
```

- `coEvery` / `coVerify`: suspend 함수에 사용
- `every` / `verify`: 일반 함수에 사용
- MockK는 내부적으로 coroutine 컨텍스트를 처리해주므로 suspend 함수도 자연스럽게 mock 가능

#### relaxed mock

```kotlin
val ack = mockk<Acknowledgment>(relaxed = true)
```

`relaxed = true`이면 stub되지 않은 메서드 호출 시 예외 대신 기본값(0, false, null 등)을 반환한다. `Acknowledgment`처럼 void 메서드만 있는 인터페이스에 적합하다.

---

### 6. Slot — 저장된 값 캡처 및 검증

`slot`은 mock에 전달된 인자를 캡처해서 내부 상태까지 검증할 수 있다.

```kotlin
val savedSlot = slot<Orders>()
val outboxSlot = slot<OutboxEvent>()

coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

service.cancelOrder("order-001", request)

// 반환값이 아닌 저장된 객체의 내부 상태를 검증
savedSlot.captured.status shouldBe OrderStatus.CANCELLING
savedSlot.captured.cancellationStatus?.compensationStatus shouldBe CompensationStatus.IN_PROGRESS
outboxSlot.captured.topic shouldBe "delivery-command"
outboxSlot.captured.aggregateId shouldBe "order-001"
```

**`any()` vs `slot`의 차이:**

```kotlin
// any() 사용: 저장됐다는 사실만 검증
coVerify { orderRepository.save(any()) }

// slot 사용: 정확히 어떤 상태로 저장됐는지 검증
savedSlot.captured.status shouldBe OrderStatus.CANCELLING  // 내부 상태까지 검증
```

`slot`이 중요한 이유: 서비스가 DB에 저장하기 전에 내부적으로 어떻게 객체를 변환했는지 (상태 변경, 필드 설정)를 직접 검증할 수 있다. 반환값만 보면 알 수 없는 중간 변환 로직을 테스트할 때 필수적이다.

---

### 7. shouldThrow — 예외 검증

```kotlin
then("IllegalStateException이 발생한다") {
    shouldThrow<IllegalStateException> {
        service.cancelOrder("order-001", cancelRequest)
    }
}
```

Kotlin의 `assertThrows`와 동일한 역할이지만 Kotest 친화적인 DSL이다. suspend 함수에도 사용 가능하다.

---

### 8. Kotest + MockK 조합 베스트 프랙티스 정리

```kotlin
class OrderServiceTest : BehaviorSpec({

    // 1. IsolationMode로 코루틴 상태 격리
    isolationMode = IsolationMode.InstancePerTest

    // 2. afterEach로 mock 정리 (beforeEach는 when setup 이후 실행되므로 부적합)
    afterEach { clearAllMocks() }

    // 3. Spec 레벨에서 mock 선언 (InstancePerTest로 인해 매 테스트마다 새로 초기화됨)
    val orderRepository = mockk<OrderRepository>()
    val outboxRepository = mockk<OutboxRepository>()

    // 4. relaxed mock은 void 메서드가 많은 인프라 객체에 사용
    val ack = mockk<Acknowledgment>(relaxed = true)

    // 5. 픽스처 함수로 반복 코드 제거
    fun baseOrder(status: OrderStatus = PENDING) = Orders(...)

    given("cancelOrder") {
        `when`("PAYMENT_COMPLETED 상태") {

            // 6. slot으로 저장된 객체의 내부 상태까지 검증
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById(any()) } returns baseOrder(PAYMENT_COMPLETED)
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            service.cancelOrder("order-001", CancelOrderRequest("변심"))

            // 7. 하나의 when에 여러 then으로 다양한 측면 검증
            then("status가 CANCELLING으로 변경된다") {
                savedSlot.captured.status shouldBe CANCELLING
            }

            then("Outbox 이벤트가 올바른 topic으로 저장된다") {
                outboxSlot.captured.topic shouldBe "delivery-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
            }
        }
    }
})
```
