# Inventory Service API

Base URL: `http://localhost:9004` (local) / `http://gateway:8080` (via gateway)

---

## 재고 등록

```
POST /v1/inventory
```

**Request**
```json
{
  "productId": "prod-001",
  "optionId": "opt-red-L",
  "location": "WAREHOUSE_A",
  "initialStock": 100,
  "safetyStock": 10
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| productId | String | Y | 상품 ID |
| optionId | String | N | 옵션 ID (색상/사이즈 등) |
| location | String | Y | 창고 위치 코드 |
| initialStock | Int | Y | 초기 재고 수량 |
| safetyStock | Int | N | 안전 재고 (기본값 0) |

**Response** `201 Created`
```json
{
  "inventoryId": "inv-uuid",
  "productId": "prod-001",
  "optionId": "opt-red-L",
  "location": "WAREHOUSE_A",
  "currentStock": 100,
  "reservedStock": 0,
  "availableStock": 100,
  "safetyStock": 10,
  "updatedAt": "2026-02-24T10:00:00Z"
}
```

**Error**
- `409 Conflict`: 동일 (productId, optionId, location) 재고 이미 존재

---

## 재고 단건 조회

```
GET /v1/inventory/{inventoryId}
```

**Response** `200 OK`
```json
{
  "inventoryId": "inv-uuid",
  "productId": "prod-001",
  "optionId": "opt-red-L",
  "location": "WAREHOUSE_A",
  "currentStock": 95,
  "reservedStock": 5,
  "availableStock": 90,
  "safetyStock": 10,
  "updatedAt": "2026-02-24T10:05:00Z"
}
```

**Error**
- `404 Not Found`: 재고 없음

---

## 상품별 재고 목록 조회

```
GET /v1/inventory?productId={productId}
```

같은 상품의 창고별 재고를 모두 반환합니다.

**Response** `200 OK` (Streaming)
```json
[
  {
    "inventoryId": "inv-uuid-1",
    "productId": "prod-001",
    "optionId": "opt-red-L",
    "location": "WAREHOUSE_A",
    "currentStock": 95,
    "reservedStock": 5,
    "availableStock": 90,
    "safetyStock": 10,
    "updatedAt": "2026-02-24T10:05:00Z"
  },
  {
    "inventoryId": "inv-uuid-2",
    "productId": "prod-001",
    "optionId": "opt-red-L",
    "location": "WAREHOUSE_B",
    "currentStock": 50,
    "reservedStock": 0,
    "availableStock": 50,
    "safetyStock": 5,
    "updatedAt": "2026-02-24T09:00:00Z"
  }
]
```

---

## 재고 수량 조정

```
PATCH /v1/inventory/{inventoryId}/stock
```

입고(+) 또는 수동 출고(-)를 처리합니다.
Saga의 자동 예약/해제(`reservedStock`)와는 별개로 `currentStock`을 직접 조정합니다.

**Request**
```json
{
  "quantityDelta": 50,
  "referenceId": "purchase-order-001",
  "referenceSource": "WMS_SYSTEM",
  "userId": "admin-001"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| quantityDelta | Int | Y | 변경 수량. 양수 = 입고(STOCK_IN), 음수 = 출고(STOCK_OUT_MANUAL) |
| referenceId | String | Y | 연관 문서 ID (입고전표, 주문 ID 등) |
| referenceSource | Enum | Y | `ORDER_SERVICE` / `ADMIN_ADJUSTMENT` / `WMS_SYSTEM` |
| userId | String | N | 처리 담당자 ID |

**Response** `200 OK`
```json
{
  "inventoryId": "inv-uuid",
  "productId": "prod-001",
  "optionId": "opt-red-L",
  "location": "WAREHOUSE_A",
  "currentStock": 145,
  "reservedStock": 5,
  "availableStock": 140,
  "safetyStock": 10,
  "updatedAt": "2026-02-24T11:00:00Z"
}
```

**Error**
- `404 Not Found`: 재고 없음
- `422 Unprocessable Entity`: 재고 부족 (출고 요청 수량 > 현재 재고)

---

## 재고 삭제

```
DELETE /v1/inventory/{inventoryId}
```

**Response** `204 No Content`

**Error**
- `404 Not Found`: 재고 없음

---

## 재고 변동 이력 조회

```
GET /v1/inventory/{inventoryId}/history
```

재고 변동 내역을 최신순으로 반환합니다.

**Response** `200 OK` (Streaming)
```json
[
  {
    "inventoryHistoryId": "hist-uuid-1",
    "inventoryId": "inv-uuid",
    "productId": "prod-001",
    "optionId": "opt-red-L",
    "location": "WAREHOUSE_A",
    "changeType": "ORDER_RESERVE",
    "quantityDelta": 2,
    "stockType": "RESERVED_STOCK",
    "beforeStock": 3,
    "afterStock": 5,
    "referenceId": "saga-order-uuid",
    "referenceSource": "ORDER_SERVICE",
    "userId": null,
    "timestamp": "2026-02-24T10:05:00Z"
  },
  {
    "inventoryHistoryId": "hist-uuid-2",
    "inventoryId": "inv-uuid",
    "productId": "prod-001",
    "optionId": "opt-red-L",
    "location": "WAREHOUSE_A",
    "changeType": "STOCK_IN",
    "quantityDelta": 50,
    "stockType": "CURRENT_STOCK",
    "beforeStock": 45,
    "afterStock": 95,
    "referenceId": "purchase-order-001",
    "referenceSource": "WMS_SYSTEM",
    "userId": "admin-001",
    "timestamp": "2026-02-24T09:00:00Z"
  }
]
```

### changeType 종류

| 값 | 설명 |
|---|---|
| `ORDER_RESERVE` | Saga 재고 예약 (reservedStock 증가) |
| `ORDER_CANCEL` | Saga 재고 해제 (reservedStock 감소) |
| `STOCK_IN` | 입고 (currentStock 증가) |
| `STOCK_OUT_MANUAL` | 수동 출고 (currentStock 감소) |
| `AUDIT_ADJUSTMENT` | 실사 조정 |

---

## 재고 개념

```
currentStock   = 실제 보유 재고
reservedStock  = Saga가 예약 중인 수량 (결제 완료 전)
availableStock = currentStock - reservedStock (실제 판매 가능 수량)
safetyStock    = 안전 재고 기준선 (알림용, 강제 적용되지 않음)
```
