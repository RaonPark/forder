# Delivery-Service 설계 결정 문서

> 작성일: 2026-03-02
> 대상: `delivery-service` 전체 구현 및 테스트
> 목적: 구현 이유, 신경 쓴 부분, 각 결정의 근거를 기록한다

---

## 목차

1. [배송 도메인 지식](#1-배송-도메인-지식)
2. [서비스 역할과 책임 경계](#2-서비스-역할과-책임-경계)
3. [Kafka / Saga 신뢰성 설계](#3-kafka--saga-신뢰성-설계)
4. [동시성 제어 전략](#4-동시성-제어-전략)
5. [트랜잭션 경계 설계](#5-트랜잭션-경계-설계)
6. [데이터 정합성 패턴](#6-데이터-정합성-패턴)
7. [API 설계 원칙](#7-api-설계-원칙)
8. [테스트 전략](#8-테스트-전략)

---

## 1. 배송 도메인 지식

배송 시스템을 올바르게 구현하려면 물리 세계의 제약과 비즈니스 규칙을 코드에 정확히 반영해야 한다.
소프트웨어 버그가 실제 택배 기사 동선 낭비, 고객 불만, 환불 분쟁으로 직결된다.

---

### 1-1. 배송 상태 머신 — 왜 일방향인가

```
PENDING → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED
                                                      ↘ DELIVERY_FAILED → OUT_FOR_DELIVERY (재배달)
                                                                        ↘ RETURNED_TO_SENDER
  ↑ (모든 구간에서 취소 가능)
  └── RETURNED_TO_SENDER  (Saga 취소 커맨드 또는 물리적 반송)
```

**상태 전이가 단방향인 물리적 이유**:
- 택배는 시간의 흐름에 따라 위치가 변한다. PICKED_UP 상태에서 PENDING으로 돌아갈 수 없다 — 물건이 이미 집화장에 있기 때문이다.
- 상태를 역행시키면 이력 데이터가 오염되고, 배송기사 앱의 업무 지시와 DB 상태가 불일치한다.
- DELIVERED 이후에는 어떤 상태로도 전이할 수 없다. 물건이 수령인에게 인도된 물리적 사실은 취소되지 않는다.

**현재 구현의 허용 전이표**:

| From | To (허용) | 이유 |
|------|----------|------|
| `PENDING` | `PICKED_UP` | 기사가 집화 |
| `PENDING` | `RETURNED_TO_SENDER` | 주문 취소 (Saga) |
| `PICKED_UP` | `IN_TRANSIT` | 허브 간 이동 시작 |
| `PICKED_UP` | `RETURNED_TO_SENDER` | 집화 후 취소 (단, 실물은 이미 기사 보유 중) |
| `IN_TRANSIT` | `OUT_FOR_DELIVERY` | 배달 담당 허브 도착 |
| `IN_TRANSIT` | `RETURNED_TO_SENDER` | 이동 중 취소 |
| `OUT_FOR_DELIVERY` | `DELIVERED` | 배달 완료 |
| `OUT_FOR_DELIVERY` | `DELIVERY_FAILED` | 부재, 주소 불명 등 |
| `DELIVERY_FAILED` | `OUT_FOR_DELIVERY` | 재배달 시도 |
| `DELIVERY_FAILED` | `RETURNED_TO_SENDER` | 재배달 포기 |

**⚠️ 주의**: `OUT_FOR_DELIVERY → RETURNED_TO_SENDER` 직접 전이는 허용하지 않는다.
배달 시도 중 실패가 발생하면 반드시 `DELIVERY_FAILED`를 거쳐야 한다.
이 단계를 건너뛰면 "왜 반송됐는지" 이력이 사라져 고객 CS 처리가 불가능해진다.

---

### 1-2. DELIVERED 이후 취소 불가 — 핵심 비즈니스 규칙

```kotlin
if (delivery.status == DeliveryStatus.DELIVERED)
    throw InvalidDeliveryOperationException("이미 배달 완료된 배송은 취소할 수 없습니다")
```

**이유**: DELIVERED 상태는 수령인이 물건을 받았다는 **법적 인도 완료**를 의미한다.
이 이후의 고객 변심, 불량 등은 **반품(Return)** 프로세스를 통해야 한다.
시스템이 이를 강제하지 않으면 결제는 환불됐는데 물건은 고객이 보유한 상태가 될 수 있다.

---

### 1-3. `RETURNED_TO_SENDER`의 의미 — 취소와 반송은 다르다

`RETURNED_TO_SENDER`는 두 가지 경로로 도달한다:

| 경로 | 원인 | 물리 상태 |
|------|------|---------|
| Saga 취소 커맨드 | 주문 취소 처리 | 물건이 아직 센터/기사 보유 중 |
| 배달 실패 후 반송 | `DELIVERY_FAILED → RETURNED_TO_SENDER` | 배달 시도했으나 실패 |

두 경우 모두 DB에서는 동일한 최종 상태이지만, **실제 운영에서는 처리 방법이 다르다**.
Saga 취소는 창고 재입고로 이어지고, 배달 실패 반송은 택배사 처리 후 재입고로 이어진다.
현재 구현에서는 이 구분이 `DeliveryHistory` 이력에 기록된 `location`/`description`으로만 추적되므로, 이력 기록을 성실하게 남기는 것이 중요하다.

---

### 1-4. 반품 배송 상태 머신 — 일반 배송과 방향이 반대

```
PICKUP_REQUESTED → PICKUP_COMPLETED → RETURNING → ARRIVED_AT_WAREHOUSE
                                                         ↓
                                          INSPECTION_COMPLETED (재판매 가능)
                                          INSPECTION_FAILED    (재판매 불가)
```

**각 상태의 물리적 의미**:

| 상태 | 의미 | 주의사항 |
|------|------|---------|
| `PICKUP_REQUESTED` | 고객 집에서 픽업 대기 | 기사가 아직 방문 전 |
| `PICKUP_COMPLETED` | 기사가 고객 집에서 상품 수거 완료 | 이 시점에 고객은 물건을 넘김 |
| `RETURNING` | 반품 상품이 창고로 이동 중 | |
| `ARRIVED_AT_WAREHOUSE` | 창고 도착 | **검수는 반드시 이 상태에서만 수행** |
| `INSPECTION_COMPLETED` | 검수 완료 (재판매 가능) | 재고 복원 → 환불 트리거 |
| `INSPECTION_FAILED` | 검수 완료 (재판매 불가) | 폐기 또는 고객 귀책 처리 |

**⚠️ 주의**: `PICKUP_REQUESTED → ARRIVED_AT_WAREHOUSE` 직접 전이는 불가능하다.
`PICKUP_COMPLETED`(수거 완료)를 반드시 거쳐야 한다.
이 단계를 건너뛰면 "기사가 상품을 실제로 수거했는가"를 추적할 수 없어, 반품 분쟁 시 증거가 없어진다.

---

### 1-5. 검수(Inspection) — 환불 금액의 근거

```kotlin
data class InspectionResult(
    val isResellable: Boolean,        // 재판매 가능 여부
    val faultType: FaultType,         // 귀책 사유
    val inspectorComment: String? = null
)
```

**검수 결과가 중요한 이유**:
검수는 단순한 상태 변경이 아니다. 검수 결과에 따라 **환불 금액과 귀책자**가 결정된다.

| `FaultType` | 귀책자 | 환불 처리 |
|-------------|-------|---------|
| `CUSTOMER_CHANGE_OF_MIND` | 고객 | 배송비 고객 부담 |
| `CUSTOMER_MISORDER` | 고객 | 배송비 고객 부담 |
| `PRODUCT_DEFECT` | 판매자 | 전액 환불 |
| `SELLER_FAULT` | 판매자 | 전액 환불 + 패널티 가능 |
| `COURIER_FAULT` | 배송사 | 배송사 배상 청구 |
| `SYSTEM_ERROR` | 플랫폼 | 플랫폼 부담 |
| `OTHER` | 케이스별 판단 | CS 수동 처리 |

**현재 구현의 한계**: `completeInspection`이 `isResellable` 여부로만 최종 상태를 결정한다. `faultType`은 저장되지만, 환불 금액 계산 로직과 연동되지 않는다. 미래에 Order-service가 반품 완료 이벤트를 수신할 때 `faultType`을 기반으로 환불 금액을 차등 처리해야 한다.

---

### 1-6. 배송 주소 — 물리 세계와의 접점

```kotlin
data class DeliveryAddress(
    val receiverName: String,
    val receiverPhone: String,
    val zipCode: String,
    val baseAddress: String,
    val detailAddress: String,
    val message: String? = null   // 배달 요청 메시지 (문 앞, 경비실 등)
)
```

**주소 관련 주의사항**:

1. **주소는 배송 생성 시 스냅샷으로 저장한다**. 고객이 나중에 회원정보의 주소를 변경해도 진행 중인 배송에 영향을 줘선 안 된다. `Delivery.destination`은 주문 시점의 불변 스냅샷이다.

2. **`zipCode`가 공백으로 저장될 수 있다**. 현재 Saga 커맨드(`DeliveryCommand.proto`)에는 zipCode 필드가 없다. Saga를 통해 생성된 배송은 zipCode가 빈 문자열로 저장된다. 향후 Proto 스키마를 확장할 때 이 필드를 추가해야 한다.

3. **`message`(배달 요청 메시지)가 배달 성공률에 영향을 준다**. "경비실에 맡겨주세요", "문 앞에 놔주세요" 같은 메시지를 기사 앱에 전달해야 부재 시 배달 실패율이 낮아진다. 현재는 저장만 되고 기사 앱 연동이 없다.

---

### 1-7. 기사 배정 — 단순해 보이지만 복잡한 문제

**현재 구현**: 활성 기사 중 첫 번째(`findFirstByIsActiveTrue`)를 자동 배정한다.

```kotlin
val courierId = request.courierId ?: courierRepository.findFirstByIsActiveTrue()?.courierId
```

**실제 배송 시스템에서 기사 배정이 복잡한 이유**:

| 고려 요소 | 설명 |
|----------|------|
| 지역 기반 배정 | 기사마다 담당 구역이 있다. 서울 강남 기사에게 인천 배송을 배정하면 안 된다 |
| 업무량 균등 | 특정 기사에게만 배송이 몰리면 SLA를 맞출 수 없다 |
| 차량 용량 | 기사의 현재 적재량을 초과하는 배정 불가 |
| 긴급 배송 | 당일 배송 주문은 별도 우선 배정 로직 필요 |
| 기사 상태 | 식사 중, 운전 중 등 실시간 상태 반영 필요 |

**현재 구현의 적합한 사용 케이스**: 소규모 내부 배송이나 MVP 단계. 실제 물류 운영 규모로 확장할 때는 배정 알고리즘을 별도 서비스로 분리해야 한다.

---

### 1-8. 배달 실패(DELIVERY_FAILED) — 예외 상황 처리

배달 실패는 실제 운영에서 자주 발생한다. 대표적 원인:
- 수령인 부재
- 주소 불명 (상세 주소 오기입)
- 수령인 수취 거부
- 엘리베이터 없는 고층 접근 불가
- 공동현관 비밀번호 불일치

**현재 구현**: `DELIVERY_FAILED → OUT_FOR_DELIVERY` 재시도 전이가 허용된다. 하지만 **재시도 횟수 제한이 없다**. 실제 배송 시스템에서는 보통 3회 시도 후 반송으로 처리한다. 횟수 제한을 `Delivery` Document에 `deliveryAttempts: Int` 필드로 추적하고, 임계값 초과 시 자동으로 `RETURNED_TO_SENDER`로 전이하는 로직이 필요하다.

---

### 1-9. 반품 취소와 일반 취소의 차이

| 구분 | 트리거 | 대상 컬렉션 | 재고 복원 시점 |
|------|-------|------------|-------------|
| 주문 취소 | Saga `CANCEL` 커맨드 | `deliveries` | 즉시 (Saga 보상 트랜잭션) |
| 반품 완료 | `INSPECTION_COMPLETED` | `return_deliveries` | 검수 완료 후 (별도 이벤트) |

주문 취소는 Saga에 의해 자동화되지만, **반품 완료 후 재고 복원은 현재 자동화되지 않았다**.
검수 완료 시 Order-service 또는 Inventory-service로 이벤트를 발행해야 재고가 복원된다.

---

## 2. 서비스 역할과 책임 경계

Delivery-service는 Saga의 3단계(`delivery-command` → `delivery-reply`)를 담당한다.

Order-service가 Saga Orchestrator로서 커맨드를 발행하면, delivery-service는 이를 소비하고 결과를 reply topic으로 반환한다.

```
[Order-service Outbox]
       ↓ Debezium CDC
[delivery-command topic]
       ↓
[DeliveryCommandHandler] ← delivery-service
       ↓
[delivery-reply topic]
       ↓
[Order-service Reply Consumer]
```

**핵심 요구사항**:
- Kafka at-least-once 환경에서 **중복 커맨드에 대한 멱등성** 보장
- 도메인 실패와 인프라 실패를 명확히 구분해 **잘못된 보상 트랜잭션 방지**
- 동시 요청 처리 시 **데이터 정합성** 유지

---

## 3. Kafka / Saga 신뢰성 설계

### 2-1. `kafkaTemplate.send().await()` — Fire-and-Forget 금지

**문제**: send()가 실패해도 ack를 commit하면 Order-service는 reply를 영원히 받지 못해 Saga가 stuck된다.

```kotlin
// 위험: 브로커 장애 시 send 실패해도 offset이 commit됨
kafkaTemplate.send("delivery-reply", sagaId, reply)
ack.acknowledge()
```

**결정**: `send().await()`로 Kafka 브로커의 실제 수신 확인 후 ack를 commit한다.

```kotlin
kafkaTemplate.send("delivery-reply", sagaId, reply).await()  // send 실패 시 예외 발생
ack.acknowledge()                                             // 성공 시에만 offset commit
```

**결과**: send 실패 → 예외 propagate → ack 미발행 → Kafka DefaultErrorHandler가 재시도.
Kafka가 커맨드를 다시 전달하므로 Saga는 자동으로 복구된다.

---

### 2-2. 도메인 예외 vs 인프라 예외 분리

**문제**: `catch (e: Exception)`으로 모든 예외를 잡으면 MongoDB 접속 실패, 네트워크 단절 같은 인프라 오류도 `success=false`로 Order-service에 전달된다.
Order-service는 이를 배송 처리 실패로 오판해 불필요한 보상 트랜잭션(결제 환불, 재고 복원)을 실행한다.

**결정**: **도메인 예외만** catch해서 reply를 보내고, 인프라 예외는 propagate한다.

```kotlin
try {
    val deliveryId = deliveryService.createDeliveryFromSaga(...)
    sendReply(sagaId, success = true, deliveryId = deliveryId)
} catch (e: InvalidDeliveryOperationException) {
    // 도메인 실패 → success=false reply → Order-service가 보상 트랜잭션 실행
    sendReply(sagaId, success = false, reason = e.message)
}
// 인프라 예외(MongoException, IOException 등)는 propagate
// → ack 미발행 → Kafka DefaultErrorHandler 재시도
// → 일시적 장애 복구 후 자동 재처리
```

**판단 기준**:

| 예외 종류 | 처리 방법 | 이유 |
|----------|----------|------|
| `InvalidDeliveryOperationException` | `success=false` reply | 재시도해도 결과 안 바뀜 (비즈니스 규칙) |
| `MongoException`, `IOException` 등 | propagate | 재시도하면 성공할 수 있음 (일시적 장애) |

---

## 4. 동시성 제어 전략

### 3-1. `cancelDeliveryFromSaga` — 트랜잭션 제거 + 낙관적 잠금

**문제**: Kafka at-least-once 환경에서 동일 cancel 커맨드가 2회 이상 배달될 수 있다.
두 스레드가 동시에 같은 배송을 취소하려 할 때, 한 쪽은 실패한다.

**초기 접근**: `@Transactional` + `OptimisticLockingFailureException` catch
→ 단독형(standalone) MongoDB에서는 동작하지만, **Replica Set 환경에서는 실패**한다.

**왜 Replica Set에서 실패하는가**:

```
[Standalone]
  T1, T2 동시 save
  → Spring Data @Version 검사 실패
  → OptimisticLockingFailureException (애플리케이션 레벨)

[@Transactional + Replica Set]
  T1, T2 각자 MongoDB 트랜잭션 시작
  → T1이 먼저 Document 수정 및 커밋
  → T2가 같은 Document 수정 시도
  → MongoDB가 서버 레벨에서 트랜잭션 중단
  → WriteConflict (error code 112, TransientTransactionError)
  → @Version 검사 자체가 실행되지 않음
  → catch (OptimisticLockingFailureException)에 잡히지 않음
  → 트랜잭션이 이미 abort됨 → catch 블록 내 findByOrderId도 실패
```

**결정**: `@Transactional` 제거.

`cancelDeliveryFromSaga`는 **단일 Document** 조회 후 **단일 Document** 저장이다.
다중 Document 원자성이 필요 없으므로 트랜잭션은 불필요하다.
트랜잭션 없이 `save()`를 호출하면 MongoDB의 단일 Document 원자 연산 + Spring Data의 `@Version` 낙관적 잠금이 동작한다.

```kotlin
// @Transactional 제거 → @Version 낙관적 잠금이 정상 동작
suspend fun cancelDeliveryFromSaga(orderId: String) {
    // ...
    try {
        deliveryRepository.save(delivery.copy(status = DeliveryStatus.RETURNED_TO_SENDER))
    } catch (e: OptimisticLockingFailureException) {
        // 동시 취소 → 재조회 후 이미 취소됐으면 정상 반환
        val current = deliveryRepository.findByOrderId(orderId)
        if (current?.status == DeliveryStatus.RETURNED_TO_SENDER) return
        throw e
    }
}
```

**동시 처리 흐름**:
```
T1: findByOrderId → PENDING(v=0)
T2: findByOrderId → PENDING(v=0)
T1: save(v=0→1) → 성공, RETURNED_TO_SENDER
T2: save(v=0→1) → OptimisticLockingFailureException
T2: catch → findByOrderId → RETURNED_TO_SENDER → return (정상)
```

**결론**: 멱등성 달성. 두 요청 모두 성공으로 처리된다.

---

### 3-2. `@Version` 낙관적 잠금 — 모든 Document에 적용

**원칙**: read-then-save 패턴이 있는 모든 Document에는 `@Version`이 필수다.

| Document | `@Version` 필요 이유 |
|----------|---------------------|
| `Delivery` | `updateStatus`, `assignCourier`, `cancelDeliveryFromSaga` |
| `Courier` | `deactivateCourier` (활성 상태 변경) |
| `ReturnDelivery` | `updateStatus`, `completeInspection` |

`@Version` 없이 동시 save가 발생하면 **Last-Write-Wins**로 한 쪽 변경이 조용히 사라진다.
이는 감지하기 어려운 데이터 손실이다.

---

### 3-3. `createDeliveryFromSaga` — 2중 중복 방어

Kafka at-least-once로 동일 sagaId 커맨드가 2회 배달될 때의 멱등성:

```kotlin
// 1차 방어: 애플리케이션 레벨 (fast path)
deliveryRepository.findBySagaId(sagaId)?.let { return it.deliveryId }

// 2차 방어: 분산 환경 동시 처리 (race condition)
try {
    deliveryRepository.save(newDelivery)
} catch (e: DuplicateKeyException) {
    // sagaId unique index 위반 → 이미 다른 스레드가 저장함
    deliveryRepository.findBySagaId(sagaId)?.deliveryId ?: throw e
}
```

**왜 2단계인가**: 두 스레드가 동시에 1차 방어를 통과하면 둘 다 insert를 시도한다.
MongoDB의 `sagaId` unique index가 두 번째 insert를 막고 `DuplicateKeyException`을 던진다.
이를 catch해서 기존 document의 deliveryId를 반환하면 멱등성이 완성된다.

---

## 5. 트랜잭션 경계 설계

**원칙**: 트랜잭션은 꼭 필요한 곳에만 적용한다.

| 메서드 | 트랜잭션 여부 | 이유 |
|--------|-------------|------|
| `createDelivery` | `@Transactional` | 단일 Document이지만 미래 확장 고려 |
| `updateStatus` | `@Transactional` | `deliveryRepository` + `deliveryHistoryRepository` 2개 Document 동시 저장 필요 |
| `assignCourier` | `@Transactional` | 단일 Document, 관례적 적용 |
| `createDeliveryFromSaga` | `@Transactional` | `DuplicateKeyException` 처리 후 재조회 원자성 |
| `cancelDeliveryFromSaga` | **없음** | 단일 Document, `@Version` 낙관적 잠금으로 충분. 트랜잭션이 오히려 WriteConflict를 유발 |

**`updateStatus`에 `@Transactional`이 필요한 이유**:
```
deliveryRepository.save(updated)         // Delivery Document 갱신
deliveryHistoryRepository.save(history)  // DeliveryHistory Document 생성
```
두 저장 중 하나가 실패하면 배송 상태와 이력이 불일치한다.
트랜잭션으로 둘을 묶어 원자적으로 처리한다.

---

## 6. 데이터 정합성 패턴

### 5-1. `@LastModifiedDate`는 `var + null` 조합 필수

```kotlin
// 잘못된 패턴 — Spring Data가 갱신할 수 없음
@LastModifiedDate
val updatedAt: Instant = Instant.now()  // val: 불변, Instant.now(): 초기화됨

// 올바른 패턴
@LastModifiedDate
var updatedAt: Instant? = null  // var: Spring Data가 리플렉션으로 갱신 가능
                                // null: Spring Data가 최초 저장 시 주입
```

`val`로 선언하면 Spring Data의 리플렉션 기반 갱신이 동작하지 않는다.
`Instant.now()` 기본값을 주면 Spring Data가 값이 이미 있다고 판단해 덮어쓰지 않는다.
결과: 상태가 100번 변해도 `updatedAt`은 생성 시각 그대로 유지되는 숨은 버그가 된다.

---

### 5-2. DTO와 Document 분리 — 내부 타입 노출 금지

```kotlin
// 잘못된 패턴 — 내부 구현이 API 계약이 됨
fun getReturnDeliveriesByOrderId(orderId: String): Flow<ReturnDelivery>

// 올바른 패턴 — API 계약과 내부 구현 분리
fun getReturnDeliveriesByOrderId(orderId: String): Flow<ReturnDeliveryResponse>
```

Document를 직접 반환하면:
- MongoDB 스키마 변경이 곧 API breaking change가 됨
- `@Document`, `@Id`, `@Version` 등 내부 어노테이션 정보가 노출됨
- 클라이언트가 내부 구현에 의존하게 됨

DTO(Response 클래스)를 통해 공개할 필드를 명시적으로 선택한다.

---

## 7. API 설계 원칙

### 6-1. 입력 검증 — 시스템 경계에서만 수행

외부 입력은 Controller 레벨에서 `@Valid` + Bean Validation으로 검증한다.
내부 서비스 간 호출(Saga 커맨드)은 Protobuf 스키마가 타입을 보장하므로 추가 검증 불필요.

```kotlin
// Controller
@PostMapping
suspend fun createDelivery(@Valid @RequestBody request: CreateDeliveryRequest): ...

// DTO
data class CreateDeliveryRequest(
    @field:NotBlank val orderId: String,
    @field:NotBlank val userId: String,
    @field:Valid val destination: DeliveryAddress,
    @field:NotEmpty val items: List<DeliveryItem>
)
```

### 6-2. `courierId` 존재 검증

```kotlin
// assignCourier: courierId 사전 검증
courierRepository.findById(courierId)
    ?: throw CourierNotFoundException("배송기사를 찾을 수 없습니다 - courierId=$courierId")
```

DB에 없는 외래 키를 저장하면 나중에 조회 시 `NullPointerException`이 발생한다.
저장 전 존재 검증으로 명시적인 에러 메시지를 제공한다.

---

## 8. 테스트 전략

### 7-1. 계층 분리

| 테스트 종류 | 대상 | 도구 |
|------------|------|------|
| 단위 테스트 | 비즈니스 로직, 상태 전이 | JUnit 5 + MockK |
| 통합 테스트 | DB 저장, 멱등성, 동시성 | JUnit 5 + Testcontainers |

**단위 테스트가 먼저인 이유**: 외부 의존성 없이 비즈니스 규칙을 검증할 수 있다.
`MockK`로 repository를 모킹해 상태 전이 규칙, 예외 시나리오를 격리해서 테스트한다.

**통합 테스트가 필요한 이유**: 단위 테스트로는 검증할 수 없는 것들이 있다.
- 실제 MongoDB의 `@Version` 충돌 동작
- `@Transactional` 경계 내 두 Document 저장의 원자성
- 멱등성 (동일 sagaId 중복 저장 방지)
- 동시 취소 요청 처리

---

### 7-2. Testcontainers — Replica Set 필수

**왜 Replica Set이 필요한가**:

Spring Boot에 `ReactiveMongoTransactionManager`가 등록되면, `@Transactional` 메서드 호출 시 MongoDB multi-document 트랜잭션을 시작한다. MongoDB는 **Replica Set에서만** multi-document 트랜잭션을 지원한다. Standalone MongoDB에서 트랜잭션을 시작하면 즉시 예외가 발생한다.

**구현**:

```kotlin
@Bean
@ServiceConnection
fun mongoDbContainer(): MongoDBContainer {
    return object : MongoDBContainer(DockerImageName.parse("mongo:latest")) {
        override fun start() {
            withCommand("--replSet", "rs0", "--bind_ip_all")
            super.start()
            // 컨테이너 기동 후 replica set 초기화
            execInContainer(
                "mongosh", "--eval",
                "rs.initiate({_id:'rs0', members:[{_id:0, host:'localhost:27017'}]})"
            )
        }

        // @ServiceConnection이 읽는 연결 문자열에 DB명과 directConnection 추가
        // super는 "mongodb://host:port" 반환 (DB명 없음 → Spring Data 오류)
        override fun getConnectionString(): String =
            super.getConnectionString() + "/forder?directConnection=true"
    }
}
```

**`directConnection=true`가 필요한 이유**:
단일 노드 Replica Set은 Primary 하나뿐이다. MongoDB 드라이버의 기본 동작은 Replica Set 토폴로지를 탐색하는데, Testcontainers 환경에서는 컨테이너 내부 hostname이 외부에서 접근 불가능해 토폴로지 탐색이 실패할 수 있다. `directConnection=true`로 Primary에 직접 연결한다.

---

### 7-3. `DeliveryCommandHandler` 단위 테스트 설계

Kafka 관련 코드는 `KafkaTemplate`을 MockK로 모킹해 검증한다.
핵심 시나리오:

| 시나리오 | send 결과 | ack 여부 | 이유 |
|---------|----------|---------|------|
| 도메인 성공 | 성공 | O | 정상 경로 |
| 도메인 실패 | 성공 (success=false) | O | Order-service가 보상 결정 |
| 인프라 실패 | 호출 안 됨 | X | 예외 propagate → Kafka 재시도 |
| Kafka send 실패 | 실패 | X | ack 없어야 재시도 가능 |

```kotlin
// Kafka send 실패 → ack가 불려선 안 됨
@Test
fun `Kafka send 실패 시 예외가 propagate되고 ack가 commit되지 않는다`() = runTest {
    coEvery { deliveryService.cancelDeliveryFromSaga(any()) } just Runs
    every { kafkaTemplate.send(...) } returns failedFuture()

    val result = runCatching { handler.handle(command, ack) }

    assertIs<Exception>(result.exceptionOrNull())
    verify(exactly = 0) { ack.acknowledge() }
}
```

---

## 요약: 핵심 설계 원칙

### 도메인

| 원칙 | 이유 |
|------|------|
| 상태 전이는 단방향 강제 | 물리 세계에서 역행은 불가능, 이력 오염 방지 |
| DELIVERED 후 취소 불가 | 법적 인도 완료, 이후는 반품 프로세스 |
| `DELIVERY_FAILED` 반드시 경유 | CS 처리 및 실패 원인 추적 근거 |
| 반품 `PICKUP_COMPLETED` 반드시 경유 | 수거 사실 증명, 분쟁 시 증거 |
| 검수 결과(`FaultType`)는 반드시 기록 | 환불 금액과 귀책자 결정 근거 |
| 주소는 주문 시점 스냅샷 | 이후 고객 주소 변경이 배송에 영향 주지 않도록 |

### 기술

| 영역 | 원칙 | 이유 |
|------|------|------|
| Saga 신뢰성 | send 결과 확인 후 ack | send 실패 시 Saga stuck 방지 |
| 예외 처리 | 도메인/인프라 예외 분리 | 일시적 장애를 영구 실패로 오판 방지 |
| 멱등성 | sagaId unique index + DuplicateKey 처리 | at-least-once Kafka 환경 대응 |
| 동시성 | 단일 Document → @Version, 다중 Document → @Transactional | 불필요한 트랜잭션이 WriteConflict를 유발 |
| 트랜잭션 | 다중 Document 저장 시에만 적용 | 단일 Document는 낙관적 잠금으로 충분 |
| 감사 필드 | `@LastModifiedDate`는 `var + null` | val/기본값은 Spring Data 갱신을 차단 |
| API 계약 | DTO와 Document 분리 | 내부 스키마 변경이 API breaking change가 되지 않도록 |
| 입력 검증 | 시스템 경계(Controller)에서만 | 내부는 타입 시스템과 도메인 로직이 보장 |
