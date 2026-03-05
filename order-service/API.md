# Order Service API

Base URL: `http://localhost:9001` (직접 접근) / `http://localhost:8080` (Gateway 경유)

> 모든 상태 쿼리(목록 조회, 검색)는 CQRS 원칙에 따라 **query-service**를 사용한다.
> order-service는 명령(생성·취소·반품)과 단건 조회만 제공한다.

---

## 공통

### 오류 응답

모든 오류는 동일한 형태로 반환된다.

```json
{
  "code": "ORDER_NOT_FOUND",
  "message": "Order not found. orderId=abc-123",
  "timestamp": "2026-03-05T10:00:00Z"
}
```

| HTTP 상태 | code | 발생 조건 |
|-----------|------|-----------|
| `404 Not Found` | `ORDER_NOT_FOUND` | 존재하지 않는 orderId |
| `409 Conflict` | `ORDER_STATUS_CONFLICT` | 도메인 불변식 위반 (잘못된 상태 전이) |
| `500 Internal Server Error` | `INTERNAL_ERROR` | 예상치 못한 서버 오류 |

---

## 주문 API

### 주문 생성

```
POST /v1/orders
```

**Request Body**

```json
{
  "userId": "user-001",
  "items": [
    {
      "productId": "prod-001",
      "quantity": 2,
      "priceAtOrder": 10000
    }
  ],
  "deliveryAddress": "서울시 강남구 테헤란로 123",
  "receiverName": "홍길동",
  "receiverPhone": "010-1234-5678"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `userId` | String | Y | 주문자 ID |
| `items` | Array | Y | 주문 상품 목록 (1개 이상) |
| `items[].productId` | String | Y | 상품 ID |
| `items[].quantity` | Int | Y | 수량 (1 이상) |
| `items[].priceAtOrder` | BigDecimal | Y | 주문 시점 단가 |
| `deliveryAddress` | String | Y | 배송지 주소 |
| `receiverName` | String | Y | 수령인 이름 |
| `receiverPhone` | String | Y | 수령인 연락처 |

**Response** `201 Created`

```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "totalAmount": 20000,
  "createdAt": "2026-03-05T10:00:00Z"
}
```

**동작**: 주문 생성 후 즉시 Inventory Reserve Saga를 시작한다.
Saga 완료 전까지 주문 상태는 `PENDING`이며, 완료 시 `PAYMENT_COMPLETED` → `PREPARING`으로 전이된다.

---

### 주문 단건 조회

```
GET /v1/orders/{orderId}
```

**Path Parameters**

| 파라미터 | 설명 |
|---------|------|
| `orderId` | 주문 ID |

**Response** `200 OK`

```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "user-001",
  "status": "PREPARING",
  "items": [
    {
      "productId": "prod-001",
      "quantity": 2,
      "priceAtOrder": 10000
    }
  ],
  "totalAmount": 20000,
  "deliveryAddress": "서울시 강남구 테헤란로 123",
  "receiverName": "홍길동",
  "receiverPhone": "010-1234-5678",
  "paymentTxId": "pay-tx-001",
  "deliveryId": "del-001",
  "cancellationState": null,
  "returnState": null,
  "createdAt": "2026-03-05T10:00:00Z",
  "updatedAt": "2026-03-05T10:01:00Z"
}
```

**주문 상태 전이**

```
PENDING → PAYMENT_COMPLETED → PREPARING → SHIPPING → DELIVERED
               ↓
           CANCELLING → CANCELLED
                     → CANCELLATION_FAILED
               ↓
       RETURN_REQUESTED → RETURN_IN_PROGRESS → RETURN_COMPLETED
                                             → RETURN_FAILED
```

| 상태 | 설명 |
|------|------|
| `PENDING` | 주문 생성 / Saga 진행 중 |
| `PAYMENT_COMPLETED` | 결제 완료 |
| `PREPARING` | 배송 준비 중 |
| `SHIPPING` | 배송 중 |
| `DELIVERED` | 배송 완료 |
| `CANCELLING` | 취소 Saga 진행 중 |
| `CANCELLED` | 취소 완료 |
| `CANCELLATION_FAILED` | 취소 실패 (운영팀 확인 필요) |
| `RETURN_REQUESTED` | 반품 신청 |
| `RETURN_IN_PROGRESS` | 반품 처리 중 |
| `RETURN_COMPLETED` | 반품 완료 |
| `RETURN_FAILED` | 반품 실패 |

**오류**

| 상태 | 발생 조건 |
|------|-----------|
| `404` | orderId가 존재하지 않음 |

---

### 주문 취소

```
PATCH /v1/orders/{orderId}/cancel
```

**Path Parameters**

| 파라미터 | 설명 |
|---------|------|
| `orderId` | 취소할 주문 ID |

**Request Body**

```json
{
  "reason": "고객 변심"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `reason` | String | Y | 취소 사유 |

**Response** `200 OK` — 주문 응답 (status: `CANCELLING`)

**동작**: 취소 가능 상태(`PAYMENT_COMPLETED`, `PREPARING`)에서만 허용된다.
취소 Saga 순서: 배송 취소 → 환불 → 재고 복구 → `CANCELLED`

**오류**

| 상태 | 발생 조건 |
|------|-----------|
| `404` | orderId가 존재하지 않음 |
| `409` | 취소 불가 상태 (`PENDING`, `SHIPPING`, `DELIVERED`, `CANCELLING` 등) |

---

### 반품 신청

```
POST /v1/orders/{orderId}/returns
```

**Path Parameters**

| 파라미터 | 설명 |
|---------|------|
| `orderId` | 반품할 주문 ID |

**Request Body**

```json
{
  "reason": "상품 불량"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `reason` | String | Y | 반품 사유 |

**Response** `201 Created` — 주문 응답 (status: `RETURN_REQUESTED`)

**동작**: `DELIVERED` 상태에서만 허용된다.
반품 Saga 순서: 반품 픽업 스케줄 → 환불 → 재고 복구 → `RETURN_COMPLETED`

**오류**

| 상태 | 발생 조건 |
|------|-----------|
| `404` | orderId가 존재하지 않음 |
| `409` | 반품 불가 상태 (`DELIVERED` 외 모든 상태) |

---

## Internal API (운영 전용)

> 이 경로는 Spring Cloud Gateway에서 외부에 노출하지 않는다.
> Airflow 또는 클러스터 내부에서 직접 포트(9001)로만 접근 가능하다.

### Saga 타임아웃 처리

```
POST /internal/saga/timeout/process
```

멈춘 Saga를 탐지하고 보상 처리를 실행한다. Airflow DAG에서 30분 주기로 호출한다.

**상태별 타임아웃 임계값**

| 상태 | 임계값 |
|------|--------|
| `PENDING` | 30분 |
| `CANCELLING` | 60분 |
| `RETURN_REQUESTED`, `RETURN_IN_PROGRESS` | 120분 |

**Response** `200 OK`

```json
{
  "processedCount": 3,
  "cancelledOrders": ["order-001"],
  "cancellationFailedOrders": ["order-002"],
  "returnFailedOrders": ["order-003"],
  "skippedOrders": []
}
```

| 필드 | 설명 |
|------|------|
| `processedCount` | 실제 처리된 주문 수 (skipped 제외) |
| `cancelledOrders` | 취소 완료된 orderId 목록 |
| `cancellationFailedOrders` | 취소 실패 처리된 orderId 목록 |
| `returnFailedOrders` | 반품 실패 처리된 orderId 목록 |
| `skippedOrders` | 처리 중 오류가 발생하여 건너뛴 orderId 목록 |

**멱등성**: 이미 terminal 상태(`CANCELLED`, `RETURN_COMPLETED` 등)인 주문은 쿼리 대상에서 제외되므로 중복 호출에 안전하다.
