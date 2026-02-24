# Payment Service API

Base URL: `http://localhost:9002` (local) / `http://gateway:8080` (via gateway)

---

## 결제 생성

```
POST /v1/payment
```

**Request**
```json
{
  "orderId": "order-uuid",
  "userId": "user-001",
  "totalAmount": 59000,
  "method": "CREDIT_CARD",
  "pgProvider": "TOSS_PAYMENTS"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| orderId | String | Y | 주문 ID |
| userId | String | Y | 사용자 ID |
| totalAmount | BigDecimal | Y | 결제 요청 금액 |
| method | Enum | Y | 결제 수단 (`PaymentMethod` 참고) |
| pgProvider | Enum | Y | PG사 (`PgProvider` 참고) |

**Response** `201 Created`
```json
{
  "paymentId": "pay-uuid",
  "orderId": "order-uuid",
  "userId": "user-001",
  "totalAmount": 59000,
  "status": "REQUESTED",
  "method": "CREDIT_CARD",
  "pgProvider": "TOSS_PAYMENTS",
  "pgTid": null,
  "approvalDetails": null,
  "isPartialCanceled": false,
  "canceledAmount": 0,
  "canceledAt": null,
  "createdAt": "2026-02-24T10:00:00Z",
  "updatedAt": "2026-02-24T10:00:00Z"
}
```

---

## 결제 단건 조회

```
GET /v1/payment/{paymentId}
```

**Response** `200 OK`
```json
{
  "paymentId": "pay-uuid",
  "orderId": "order-uuid",
  "userId": "user-001",
  "totalAmount": 59000,
  "status": "APPROVED",
  "method": "CREDIT_CARD",
  "pgProvider": "TOSS_PAYMENTS",
  "pgTid": "pg-tid-xxxxxxxx",
  "approvalDetails": null,
  "isPartialCanceled": false,
  "canceledAmount": 0,
  "canceledAt": null,
  "createdAt": "2026-02-24T10:00:00Z",
  "updatedAt": "2026-02-24T10:00:05Z"
}
```

**Error**
- `404 Not Found`: 결제 없음

---

## 주문별 결제 조회

```
GET /v1/payment/order/{orderId}
```

주문 ID로 해당 주문의 결제 정보를 조회합니다.

**Response** `200 OK`
```json
{
  "paymentId": "pay-uuid",
  "orderId": "order-uuid",
  "userId": "user-001",
  "totalAmount": 59000,
  "status": "APPROVED",
  "method": "CREDIT_CARD",
  "pgProvider": "TOSS_PAYMENTS",
  "pgTid": "pg-tid-xxxxxxxx",
  "approvalDetails": null,
  "isPartialCanceled": false,
  "canceledAmount": 0,
  "canceledAt": null,
  "createdAt": "2026-02-24T10:00:00Z",
  "updatedAt": "2026-02-24T10:00:05Z"
}
```

**Error**
- `404 Not Found`: 해당 주문의 결제 없음

---

## 환불 요청

```
POST /v1/payment/{paymentId}/refund
```

승인된(APPROVED) 결제에 대해 환불을 요청합니다.
환불 금액이 결제 금액을 초과할 수 없습니다.

**Request**
```json
{
  "refundType": "PARTIAL_CANCELLATION",
  "refundAmount": 20000,
  "reason": "상품 일부 불량"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| refundType | Enum | Y | 환불 유형 (`RefundType` 참고) |
| refundAmount | BigDecimal | Y | 환불 금액 |
| reason | String | N | 환불 사유 |

**Response** `200 OK`
```json
{
  "refundId": "refund-uuid",
  "paymentId": "pay-uuid",
  "orderId": "order-uuid",
  "refundType": "PARTIAL_CANCELLATION",
  "refundAmount": 20000,
  "feeAmount": 0,
  "reason": "상품 일부 불량",
  "status": "COMPLETED",
  "createdAt": "2026-02-24T11:00:00Z"
}
```

**Error**
- `404 Not Found`: 결제 없음
- `422 Unprocessable Entity`: 승인 상태가 아닌 결제 / 환불 금액 초과

---

## 환불 이력 조회

```
GET /v1/payment/{paymentId}/refunds
```

해당 결제에 대한 모든 환불 이력을 반환합니다.

**Response** `200 OK` (Streaming)
```json
[
  {
    "refundId": "refund-uuid-1",
    "paymentId": "pay-uuid",
    "orderId": "order-uuid",
    "refundType": "PARTIAL_CANCELLATION",
    "refundAmount": 20000,
    "feeAmount": 0,
    "reason": "상품 일부 불량",
    "pgRefundTid": null,
    "status": "COMPLETED",
    "createdAt": "2026-02-24T11:00:00Z"
  },
  {
    "refundId": "refund-uuid-2",
    "paymentId": "pay-uuid",
    "orderId": "order-uuid",
    "refundType": "FULL_CANCELLATION",
    "refundAmount": 39000,
    "feeAmount": 0,
    "reason": null,
    "pgRefundTid": null,
    "status": "COMPLETED",
    "createdAt": "2026-02-24T12:00:00Z"
  }
]
```

---

## Enum 타입

### PaymentStatus

| 값 | 설명 |
|---|---|
| `REQUESTED` | 결제 요청됨 (초기 상태) |
| `APPROVED` | PG 승인 완료 |
| `FAILED` | 결제 실패 |
| `CANCELED` | 환불/취소 완료 |

### PaymentMethod

| 값 | 설명 |
|---|---|
| `CREDIT_CARD` | 신용카드 |
| `DEBIT_CARD` | 체크카드 |
| `BANK_TRANSFER` | 계좌이체 |
| `CASH_ON_DELIVERY` | 착불 |
| `DIGITAL_WALLET` | 디지털 지갑 (카카오페이, 애플페이 등) |

### PgProvider

| 값 | 설명 |
|---|---|
| `NICEPAY` | 나이스페이먼츠 |
| `KCP` | KG KCP |
| `KG_INICIS` | KG 이니시스 |
| `TOSS_PAYMENTS` | 토스페이먼츠 |
| `KAKAO_PAY` | 카카오페이 |
| `APPLE_PAY` | 애플페이 |

### RefundType

| 값 | 설명 |
|---|---|
| `FULL_CANCELLATION` | 전체 취소 |
| `PARTIAL_CANCELLATION` | 부분 취소 |
| `REFUND_AFTER_DELIVERY` | 배송 후 반품 환불 |

### RefundStatus

| 값 | 설명 |
|---|---|
| `REQUESTED` | 환불 요청됨 |
| `PROCESSING` | PG 환불 처리 중 |
| `COMPLETED` | 환불 완료 |
| `FAILED` | 환불 실패 |

---

## 결제 상태 흐름

```
REQUESTED → APPROVED   (PG 승인 성공)
REQUESTED → FAILED     (PG 승인 실패)
APPROVED  → CANCELED   (환불/취소 완료)
```

## Saga 연동

주문 생성 시 Saga Orchestrator가 `payment-command` 토픽으로 명령을 전송합니다.

| command type | 처리 | reply 토픽 |
|---|---|---|
| `PROCESS` | 결제 생성 + 즉시 APPROVED (mock PG) | `payment-reply` |
| `REFUND` | 주문 ID로 결제 조회 후 환불 처리 | `payment-reply` |
