# order-service API 설계

## POST /v1/orders - 새로운 주문을 생성한다.
### parameter
- userId: String(사용자 ID)
- items: List(주문 상품 목록)
  - productId: String(주문 상품의 고유 ID)
  - quantity: Integer(주문 수량)
- deliveryAddress: String(상품을 배송받을 최종 주소)
- receiverName: String(수령인 이름)
- receiverPhone: String(수령인 연락처)
- paymentMethod: Enum(사용자가 선택한 결제 수단)
### result
1. 201 Created
    - orderId: String(주문 ID)
    - status: Enum(주문 상태)
    - totalAmount: Double(주문 상품의 금액)
    - expectedCompletion: String(최종 주문 완료까지의 예상 상태 흐름)
2. 400 Bad Request
3. 500 Internal Server Error

## PATCH /v1/orders/{orderId}/cancel - 배송 전 주문을 취소한다.
### 주문취소 Flow
1. orderId에 해당하는 Orders document의 상태를 변경한다.
2. Debezium이 MongoDB의 Change Stream을 통해 상태 변경을 즉시 감지한다.
3. Kafka Topic이 발행되어 배송 취소와 결제 취소, 재고 원복을 요청한다.
4. 모두 완료되면 최종적으로 Orders document의 상태를 변경한다.
5. Debezium이 변경을 감지하고 Kafka Streams를 통해 Elasticsearch에 최종 주문 정보를 업데이트한다.

## POST /v1/returns - 배송 후 반품을 요청한다.
### 반품 Flow
1. orderId에 해당하는 Orders document의 상태를 변경한다.
2. Debezium이 MongoDB의 Change Stream을 통해 상태 변경을 즉시 감지한다.
3. Kafka Topic이 발행되어 상품 회수, 결제 취소, 재고 원복을 요청한다.
4. 모두 완료되면 최종적으로 Orders document의 상태를 변경한다.
5. Debezium이 변경을 감지하고 Kafka Streams를 통해 Elasticsearch에 최종 주문 정보를 업데이트한다.

## PATCH /v1/orders/{orderId}/confirm-delivery - 배송완료 상태로 변경한다.
### 배송 완료 Flow
1. delivery-service에 orderId에 해당하는 배송을 배송 완료 상태로 바꾼다.
2. deliveries-cdc에 구독해 있는 debezium이 이벤트를 발송한다.
3. order-service에 있는 kafka listener가 메세지를 받고 Orders document를 바꾼다.
4. orders-cdc에 구독해 있는 debezium이 이벤트를 발송한다.
5. Elasticsearch에 있는 주문 정보의 상태를 변경한다.

## GET /v1/orders/{orderId} - 주문의 상세 정보를 조회한다.

## GET /v1/orders - 사용자의 전체 주문 목록을 조회한다.

## GET /v1/orders/reports - 주문 통계를 조회한다.