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

### 추가로 구현해야 할 것 (2026.02.24)
추가로 생각해야 할 것들

1. 멱등성 (가장 중요)

Kafka는 메시지를 재전달할 수 있습니다. 각 서비스가 동일한 Command를 두 번 처리하면 재고가 두 번 차감됩니다.
// sagaId로 이미 처리한 명령인지 확인 필요
if (alreadyProcessed(command.sagaId)) return replyWithCachedResult()

2. DLQ (Dead Letter Queue)

Command Handler에서 예외가 발생하면 Kafka가 재시도하다가 DLQ로 보냅니다. DLQ 메시지를 모니터링하고 수동/자동 재처리하는 전략이 필요합니다.
spring.kafka.listener.dead-letter-topic: inventory-command-dlt

3. 보상 Reply 처리 미구현

현재 onInventoryReply, onPaymentReply가 정방향 성공/실패만 처리하고, 보상(RELEASE/REFUND) 결과는 처리하지 않습니다. 보상도 실패할 수 있으므로 별도 처리가
필요합니다.
보상 실패 → 수동 개입 알림 or 재시도 큐

4. Saga 타임아웃

Inventory가 Reply를 영원히 안 보내면 주문이 PENDING으로 영원히 머뭅니다. Spring Batch 또는 스케줄러로 일정 시간 지난 PENDING 주문을 감지해 강제 보상해야 합니다.

5. Outbox 패턴

현재 orderRepository.save() 후 kafkaTemplate.send()를 호출하는데, 저장은 성공했지만 Kafka 발행이 실패하면 데이터가 불일치합니다. Outbox 패턴 + Debezium CDC로
원자적 발행이 가능합니다.

6. OrderRepository

현재 OrderRepository.findById()를 사용하는데, order-service의 Repository가 아직 비어 있어서 구현이 필요합니다.