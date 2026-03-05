# Product Service API

Base URL: `http://localhost:9005` (직접 접근) / `http://localhost:8080` (Gateway 경유)

> 상품 검색·브라우징은 CQRS 원칙에 따라 **query-service**를 사용한다.
> product-service는 상품 등록·수정 등 판매자/운영 명령 API만 제공한다.

---

## 공통

### 오류 응답

```json
{
  "code": "PRODUCT_NOT_FOUND",
  "message": "Product not found. productId=abc-123",
  "timestamp": "2026-03-05T10:00:00Z"
}
```

| HTTP 상태 | code | 발생 조건 |
|-----------|------|-----------|
| `404 Not Found` | `PRODUCT_NOT_FOUND` | 존재하지 않는 productId |
| `409 Conflict` | `PRODUCT_STATUS_CONFLICT` | 도메인 불변식 위반 (DISCONTINUED 상품 수정 등) |
| `500 Internal Server Error` | `INTERNAL_ERROR` | 예상치 못한 서버 오류 |

---

## 상품 API

### 상품 등록

```
POST /v1/products
```

**Request Body**

```json
{
  "sellerId": "seller-001",
  "name": "무선 이어폰 Pro",
  "description": "고음질 노이즈캔슬링 이어폰",
  "category": "전자기기",
  "brand": "SoundBrand",
  "basePrice": 89000,
  "salePrice": 79000,
  "imageUrl": "https://cdn.example.com/products/earphone.jpg",
  "tags": ["무선", "노이즈캔슬링", "신상품"]
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `sellerId` | String | Y | 판매자 ID |
| `name` | String | Y | 상품명 |
| `description` | String | Y | 상품 설명 |
| `category` | String | Y | 카테고리 |
| `brand` | String | Y | 브랜드 |
| `basePrice` | BigDecimal | Y | 정상가 |
| `salePrice` | BigDecimal | N | 할인가 (없으면 null) |
| `imageUrl` | String | Y | 대표 이미지 URL |
| `tags` | Array\<String\> | N | 태그 목록 (기본값: 빈 배열) |

**Response** `201 Created`

```json
{
  "productId": "550e8400-e29b-41d4-a716-446655440000",
  "sellerId": "seller-001",
  "name": "무선 이어폰 Pro",
  "description": "고음질 노이즈캔슬링 이어폰",
  "category": "전자기기",
  "brand": "SoundBrand",
  "basePrice": 89000,
  "salePrice": 79000,
  "status": "ON_SALE",
  "isAvailable": true,
  "imageUrl": "https://cdn.example.com/products/earphone.jpg",
  "tags": ["무선", "노이즈캔슬링", "신상품"],
  "createdAt": "2026-03-05T10:00:00Z",
  "updatedAt": "2026-03-05T10:00:00Z"
}
```

**동작**: 등록 시 상태는 항상 `ON_SALE`, `isAvailable=true`로 시작한다.

---

### 상품 단건 조회

```
GET /v1/products/{productId}
```

**Response** `200 OK` — 상품 응답 (위 응답 형식과 동일)

**오류**

| 상태 | 발생 조건 |
|------|-----------|
| `404` | productId가 존재하지 않음 |

---

### 상품 정보 수정

```
PUT /v1/products/{productId}
```

**Request Body** — 전체 필드를 모두 전송해야 한다 (PUT 전체 수정).

```json
{
  "name": "무선 이어폰 Pro 2세대",
  "description": "2세대 업그레이드 모델",
  "category": "전자기기",
  "brand": "SoundBrand",
  "basePrice": 99000,
  "salePrice": null,
  "imageUrl": "https://cdn.example.com/products/earphone-v2.jpg",
  "tags": ["무선", "신상품"],
  "isAvailable": true
}
```

**Response** `200 OK` — 수정된 상품 응답

**오류**

| 상태 | 발생 조건 |
|------|-----------|
| `404` | productId가 존재하지 않음 |
| `409` | `DISCONTINUED` 상품은 수정 불가 |

---

### 상품 상태 변경

```
PATCH /v1/products/{productId}/status
```

**Request Body**

```json
{
  "status": "HIDDEN"
}
```

**상태 전이 규칙**

```
ON_SALE ──────→ SOLD_OUT
   │               │
   ↓               ↓
HIDDEN ←────── ON_SALE
   │               │
   └──────→ DISCONTINUED ← (최종 상태, 이후 전이 불가)
```

| 상태 | `isAvailable` | 설명 |
|------|---------------|------|
| `ON_SALE` | `true` | 판매 중 |
| `SOLD_OUT` | `false` | 품절 (노출은 됨) |
| `HIDDEN` | `false` | 비노출 (관리자/판매자만 확인 가능) |
| `DISCONTINUED` | `false` | 판매 중단 (최종 상태, 수정 불가) |

> `isAvailable`은 상태 변경 시 자동으로 업데이트된다 (`ON_SALE`일 때만 `true`).

**Response** `200 OK` — 변경된 상품 응답

**오류**

| 상태 | 발생 조건 |
|------|-----------|
| `404` | productId가 존재하지 않음 |
| `409` | `DISCONTINUED` 상품은 상태 변경 불가 |

---

### 판매자별 상품 목록

```
GET /v1/products/seller/{sellerId}?status={status}
```

**Path Parameters**

| 파라미터 | 설명 |
|---------|------|
| `sellerId` | 판매자 ID |

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `status` | ProductStatus | N | 상태 필터 (`ON_SALE`, `SOLD_OUT`, `HIDDEN`, `DISCONTINUED`) |

**Response** `200 OK`

```json
[
  {
    "productId": "prod-001",
    "name": "무선 이어폰 Pro",
    "status": "ON_SALE",
    ...
  },
  {
    "productId": "prod-002",
    "name": "블루투스 스피커",
    "status": "HIDDEN",
    ...
  }
]
```

> 정렬 기준: `createdAt` 내림차순 (최신 등록 순)
