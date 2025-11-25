# order-service API 설계

## POST /v1/orders - 새로운 주문을 생성한다.
### parameter
```json
{
  "userId": "String",
  "items": [
    {
      "productId": "String",
      "quantity": "Integer"
    }
  ],
  "deliveryAddress": "String",
  "receiverName": "String",
  "receiverPhone": "String",
  "paymentMethod": "Enum"
}
```
### result
- 201 Created
```json
{
  "orderId": "String",
  "status": "Enum",
  "totalAmount": "Double",
  "expectedCompletion": "String"
}
```
- 400 Bad Request 
- 401 Unauthorized(TODO)
- 500 Internal Server Error

## PATCH /v1/orders/{orderId}/cancel - 배송 전 주문을 취소한다.
### 주문취소 Flow
1. orderId에 해당하는 Orders document의 상태를 변경한다.
2. Debezium이 MongoDB의 Change Stream을 통해 상태 변경을 즉시 감지한다.
3. Kafka Topic이 발행되어 배송 취소와 결제 취소, 재고 원복을 요청한다.
4. 모두 완료되면 최종적으로 Orders document의 상태를 변경한다.
5. Debezium이 변경을 감지하고 Kafka Streams를 통해 Elasticsearch에 최종 주문 정보를 업데이트한다.

### result
- 204 No Content
- 400 Bad Request
- 401 Unauthorized(TODO)
- 500 Internal Server Error

## POST /v1/returns - 배송 후 반품을 요청한다.
### 반품 Flow
1. orderId에 해당하는 Orders document의 상태를 변경한다.
2. Debezium이 MongoDB의 Change Stream을 통해 상태 변경을 즉시 감지한다.
3. Kafka Topic이 발행되어 상품 회수, 결제 취소, 재고 원복을 요청한다.
4. 모두 완료되면 최종적으로 Orders document의 상태를 변경한다.
5. Debezium이 변경을 감지하고 Kafka Streams를 통해 Elasticsearch에 최종 주문 정보를 업데이트한다.

### result
- 202 Accepted
```json
{
  "orderId": "String",
  "returnRequestId": "String",
  "returnStatus": "Enum",
  "requestedAt": "Instant"
}
```
- 400 Bad Request
- 401 Unauthorized(TODO)
- 500 Internal Server Error

## PATCH /v1/orders/{orderId}/confirm-delivery - 배송완료 상태로 변경한다.
### 배송 완료 Flow
1. delivery-service에 orderId에 해당하는 배송을 배송 완료 상태로 바꾼다.
2. deliveries-cdc에 구독해 있는 debezium이 이벤트를 발송한다.
3. order-service에 있는 kafka listener가 메세지를 받고 Orders document를 바꾼다.
4. orders-cdc에 구독해 있는 debezium이 이벤트를 발송한다.
5. Elasticsearch에 있는 주문 정보의 상태를 변경한다.

### result
- 204 No Content
- 400 Bad Request
- 401 Unauthorized(TODO)
- 500 Internal Server Error

## GET /v1/orders/{orderId} - 주문의 상세 정보를 조회한다.
## result
- 200 OK
```json
{
  "orderId": "String",
  "status": "Enum",
  "items": [
    {
      "productId": "String",
      "productName": "String",
      "price": "Double",
      "quantity": "Integer",
      "sellerName": "String",
      "imageUrl": "String | null"
    }
  ],
  "totalAmount": "BigDecimal",
  "deliveryAddress": "String",
  "receiverName": "String",
  "receiverPhone": "String",
  "orderedAt": "Instant",
  "paymentMethod": "Enum",
  "paymentTxId": "String",
  "paidAt": "Instant",
  
  "trackingNumber": "String",
  "deliveryStatus": "Enum",
  "estimatedDeliveryDate": "Instant",
  "actualDeliveryDate": "Instant | null",
  
  "cancelledAt": "Instant | null",
  "returnRequestId": "String | null",
  "returnStatus": "Enum | null"
}
```
- 404 Not Found

## GET /v1/orders - 사용자의 전체 주문 목록을 조회한다.
### query parameters
- userId: String (사용자 ID, optional)
- status: Enum (주문 상태 필터, optional)
- startDate: Instant (조회 시작일, optional)
- endDate: Instant (조회 종료일, optional)
- page: Integer (페이지 번호, default: 0)
- size: Integer (페이지 크기, default: 20)
- sort: String (정렬 기준, default: "orderedAt,desc")

### result
- 200 OK
```json
{
  "orderId": "String",
  "status": "Enum",
  "items": [
    {
      "productId": "String",
      "productName": "String",
      "price": "Double",
      "quantity": "Integer",
      "sellerName": "String",
      "imageUrl": "String | null"
    }
  ],
  "totalAmount": "BigDecimal",
  "deliveryAddress": "String",
  "receiverName": "String",
  "receiverPhone": "String",
  "orderedAt": "Instant",
  "paymentMethod": "Enum",
  "paymentTxId": "String",
  "paidAt": "Instant",
  
  "trackingNumber": "String",
  "deliveryStatus": "Enum",
  "estimatedDeliveryDate": "Instant",
  "actualDeliveryDate": "Instant | null",
  
  "cancelledAt": "Instant | null",
  "returnRequestId": "String | null",
  "returnStatus": "Enum | null"
}
```

## GET /v1/orders/reports - 주문 통계를 조회한다.