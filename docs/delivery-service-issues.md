# Delivery-Service 문제 분석

> 분석일: 2026-02-27
> 검토 파일: `DeliveryService`, `ReturnDeliveryService`, `CourierService`, `DeliveryCommandHandler`, `GlobalExceptionHandler`, 각 Document/DTO/Controller

---

## 요약

| # | 분류 | 문제 | 심각도 |
|---|------|------|--------|
| 1-1 | 동시성 | `cancelDeliveryFromSaga` OptimisticLock → Saga 오판 | 🔴 심각 |
| 1-2 | 동시성 | `assignCourier` 중복 배정 가능 | 🟡 중간 |
| 2-1 | 데이터 | `ReturnDelivery.updatedAt` 영구 고정 (`val`) | 🟡 중간 |
| 2-2 | 데이터 | `Courier` `@Version` 없음 → Lost Update | 🟡 중간 |
| 2-3 | 데이터 | `updateStatus` 트랜잭션 경계 주의 | 🟢 낮음 |
| 3-1 | Kafka | `send()` 결과 미확인 → Saga stuck | 🔴 심각 |
| 3-2 | Kafka | 인프라 예외 = 비즈니스 실패로 처리 → 잘못된 보상 | 🔴 심각 |
| 4-1 | API | 입력 검증 전무 | 🟡 중간 |
| 4-2 | API | courierId 존재 여부 미검증 | 🟡 중간 |
| 4-3 | API | 내부 Document 타입 직접 노출 | 🟡 중간 |
| 4-4 | API | `location` 빈 문자열 허용 | 🟢 낮음 |
| 5-1 | 운영 | 이력 조회 페이지네이션 없음 | 🟢 낮음 |

---

## 1. 동시성 (Concurrency)

### 1-1. `cancelDeliveryFromSaga` — OptimisticLock 실패가 Saga를 망가뜨림 🔴

**위치**: `DeliveryService.cancelDeliveryFromSaga()` + `DeliveryCommandHandler.cancel()`

Kafka `concurrency=3`에서 동일 cancel 커맨드가 2번 배달되면:
1. Thread-A, Thread-B 동시에 `findByOrderId` → 둘 다 `PENDING` 상태 조회
2. Thread-A 먼저 save → 성공, `success=true` reply 전송
3. Thread-B도 save 시도 → `OptimisticLockingFailureException` → `catch (e: Exception)`에 잡혀 **`success=false`** reply 전송
4. Order-service가 `success=false`를 보고 **정상적으로 취소된 배송에 대해 보상 트랜잭션 실행**

**수정 방향**:
```kotlin
} catch (e: OptimisticLockingFailureException) {
    // 재조회 후 이미 취소 완료면 success=true
    val current = deliveryRepository.findByOrderId(orderId)
    if (current?.status == DeliveryStatus.RETURNED_TO_SENDER) return
    throw e
}
```

---

### 1-2. `assignCourier` — 동시 배정 시 409 에러 노출

동시 요청이 오면 `@Version`이 충돌을 잡아 409를 반환합니다. 기능적으로는 안전하지만, 재시도하면 이미 배정된 기사가 덮어써질 수 있습니다.

---

## 2. 데이터 정합성 (Data Integrity)

### 2-1. `ReturnDelivery.updatedAt` 영구 고정 🐛 버그

**위치**: `ReturnDelivery.kt`

```kotlin
// 현재 (잘못됨)
@LastModifiedDate
val updatedAt: Instant = Instant.now()   // val + 기본값 → Spring Data가 갱신 불가
```

```kotlin
// 수정
@LastModifiedDate
var updatedAt: Instant? = null
```

`val` + `Instant.now()` 기본값 조합으로 `@LastModifiedDate`가 동작하지 않습니다. 상태 변경 후에도 `updatedAt`은 생성 시각 그대로입니다.

---

### 2-2. `Courier` — `@Version` 없음 → Lost Update

**위치**: `Courier.kt`

`deactivateCourier()`는 read-then-save 패턴인데 낙관적 락이 없습니다. 동시 요청 시 Last-Write-Wins로 데이터가 덮어써집니다.

```kotlin
// 수정: Courier.kt에 추가
@Version
val version: Long? = null
```

---

### 2-3. `updateStatus` — 트랜잭션 경계

`deliveryRepository.save()` + `deliveryHistoryRepository.save()`가 `@Transactional`로 묶여 있어 원칙상 안전합니다. 다만 Reactive MongoDB 트랜잭션의 suspend point 처리가 driver 버전에 따라 다를 수 있으므로 통합 테스트 확인 권장.

---

## 3. Kafka / Saga 신뢰성

### 3-1. `kafkaTemplate.send()` 결과를 확인하지 않음 🔴

**위치**: `DeliveryCommandHandler.handle()`

```kotlin
// 현재 (잘못됨)
kafkaTemplate.send("delivery-reply", command.sagaId, reply)  // fire-and-forget
ack.acknowledge()  // 항상 offset commit
```

Kafka 일시 장애 시 send 실패해도 offset이 commit됩니다. Order-service는 reply를 영원히 못 받아 Saga가 stuck됩니다.

```kotlin
// 수정
kafkaTemplate.send("delivery-reply", command.sagaId, reply).await()
ack.acknowledge()
```

---

### 3-2. 인프라 예외를 비즈니스 실패로 처리 → 잘못된 보상 트랜잭션 🔴

**위치**: `DeliveryCommandHandler.create()`, `cancel()`

```kotlin
// 현재 (잘못됨)
} catch (e: Exception) {  // MongoException, NetworkException 등 모두 포함
    deliveryReply { success = false }
}
```

DB 재시작, 네트워크 순단 같은 일시적 인프라 오류도 `success=false`로 Saga에 전달됩니다. Order-service는 실제로 실패하지 않은 배송에 대해 보상 트랜잭션을 실행합니다.

```kotlin
// 수정: 도메인 예외만 catch
} catch (e: InvalidDeliveryOperationException) {
    deliveryReply { success = false; failureReason = e.message }
}
// 인프라 예외는 propagate → DefaultErrorHandler가 3회 재시도
```

---

## 4. API 설계 / 입력 검증

### 4-1. 입력 검증 전무

모든 컨트롤러에 `@Valid` 없음, DTO에 Bean Validation 어노테이션 없음. 빈 문자열 `orderId`, null `userId`로 배송 생성 가능.

**수정**: `build.gradle.kts`에 `spring-boot-starter-validation` 추가 후 DTO에 `@NotBlank`, `@NotNull` 추가, 컨트롤러에 `@Valid` 추가.

---

### 4-2. `assignCourier` — courierId 존재 여부 미검증

**위치**: `DeliveryService.assignCourier()`

```kotlin
// 수정: courier 존재 확인 추가
val courier = courierRepository.findById(courierId)
    ?: throw CourierNotFoundException("배송기사를 찾을 수 없습니다 - courierId=$courierId")
```

---

### 4-3. `getReturnDeliveriesByOrderId` — 내부 Document 타입 노출

**위치**: `ReturnDeliveryController.kt`

```kotlin
// 현재 (잘못됨)
fun getReturnDeliveriesByOrderId(...): Flow<ReturnDelivery>  // 내부 Document 직접 노출

// 수정
fun getReturnDeliveriesByOrderId(...): Flow<ReturnDeliveryResponse>
```

---

### 4-4. `location` 빈 문자열 허용

`UpdateDeliveryStatusRequest.location: String` → `@NotBlank` 없어 빈 문자열로 이력 저장 가능.

---

## 5. 관찰 가능성 / 운영

### 5-1. 배송 이력 조회 페이지네이션 없음

**위치**: `DeliveryService.getHistory()`

재배송, 반품 반복 시 이력이 수백 건이 될 수 있습니다. `Pageable` 파라미터 추가 권장.

---

## 수정 우선순위

```
1순위: 3-1 (kafkaTemplate.send await)       ← Saga 자체 신뢰성 붕괴
2순위: 1-1 (cancelDeliveryFromSaga 멱등성)  ← 잘못된 보상 트랜잭션
3순위: 3-2 (예외 분리)                      ← 잘못된 보상 트랜잭션
4순위: 2-1 (ReturnDelivery updatedAt)        ← 데이터 버그
5순위: 2-2 (Courier @Version)               ← Lost Update
6순위: 4-x (API 검증)                       ← 안정성
7순위: 5-1 (페이지네이션)                   ← 성능
```
