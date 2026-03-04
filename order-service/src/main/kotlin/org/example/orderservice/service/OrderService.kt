package org.example.orderservice.service

import common.document.order.CancellationState
import common.document.order.CompensationStatus
import common.document.order.OrderStatus
import common.document.order.ReturnState
import common.saga.OrderSagaState
import common.saga.SagaStep
import common.saga.command.DeliveryCommandType
import common.saga.command.InventoryCommandType
import common.saga.command.deliveryCommand
import common.saga.command.inventoryCommand
import common.saga.command.inventoryCommandItem
import org.example.orderservice.document.OrderItem
import org.example.orderservice.document.Orders
import org.example.orderservice.document.OutboxEvent
import org.example.orderservice.dto.CancelOrderRequest
import org.example.orderservice.dto.CreateOrderRequest
import org.example.orderservice.dto.CreateOrderResponse
import org.example.orderservice.dto.OrderResponse
import org.example.orderservice.dto.ReturnOrderRequest
import org.example.orderservice.repository.OrderRepository
import org.example.orderservice.repository.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val outboxRepository: OutboxRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val INVENTORY_COMMAND_TOPIC = "inventory-command"
        const val DELIVERY_COMMAND_TOPIC  = "delivery-command"

        // PREPARING 상태 취소 허용 — 알려진 경쟁 조건 및 설계 결정:
        //
        // [경쟁 조건] delivery-service 가 이미 출고 처리 중일 때 취소 요청이 도달하면
        //   delivery cancel reply 가 실패로 돌아오고 → CANCELLATION_FAILED 로 종료된다.
        //
        // [왜 허용하는가] PAYMENT_COMPLETED → PREPARING 전환 사이 고객이 취소를 원할 수 있으며,
        //   배송 시작 전(PREPARING) 에는 취소 성공 가능성이 높다.
        //
        // [보호 장치]
        //   1. @Version 낙관적 잠금 — 동시 취소 요청 중 하나만 성공
        //   2. CANCELLATION_FAILED 시 SagaTimeoutHandler 가 환불·재고 복구를 보장
        //   3. delivery-service 는 멱등적으로 cancel 처리
        val CANCELLABLE_STATUSES = setOf(OrderStatus.PAYMENT_COMPLETED, OrderStatus.PREPARING)
    }

    // ──────────────────────────────────────────
    // 주문 생성
    // 버그 수정: order + outbox를 단일 @Transactional 블록에서 저장 (더블 세이브 제거)
    // ──────────────────────────────────────────

    @Transactional
    suspend fun createOrder(request: CreateOrderRequest): CreateOrderResponse {
        val orderId = UUID.randomUUID().toString()

        val order = Orders(
            orderId = orderId,
            userId = request.userId,
            status = OrderStatus.PENDING,
            items = request.items.map {
                OrderItem(
                    productId = it.productId,
                    quantity = it.quantity,
                    priceAtOrder = it.priceAtOrder
                )
            },
            totalAmount = request.items.sumOf { it.priceAtOrder * it.quantity.toBigDecimal() },
            deliveryAddress = request.deliveryAddress,
            receiverName = request.receiverName,
            receiverPhone = request.receiverPhone,
            sagaState = OrderSagaState(
                sagaId = orderId,
                currentStep = SagaStep.INVENTORY_RESERVE
            )
        )

        val saved = orderRepository.save(order)

        outboxRepository.save(
            OutboxEvent.of(
                aggregateId = orderId,
                topic = INVENTORY_COMMAND_TOPIC,
                message = inventoryCommand {
                    sagaId = orderId
                    type = InventoryCommandType.RESERVE
                    items += order.items.map { item ->
                        inventoryCommandItem {
                            productId = item.productId
                            quantity = item.quantity
                        }
                    }
                }
            )
        )

        log.info("[Order] Created - orderId={}", orderId)

        return CreateOrderResponse(
            orderId = saved.orderId,
            totalAmount = saved.totalAmount,
            createdAt = saved.createdAt
        )
    }

    // ──────────────────────────────────────────
    // 주문 조회
    // ──────────────────────────────────────────

    suspend fun getOrder(orderId: String): OrderResponse {
        val order = findOrderOrThrow(orderId)
        return OrderResponse.from(order)
    }

    // ──────────────────────────────────────────
    // 주문 취소
    // 도메인 불변식: PAYMENT_COMPLETED, PREPARING 상태만 취소 가능
    // PENDING 상태는 Saga 진행 중이므로 취소 불가 (경쟁 조건 방지)
    // ──────────────────────────────────────────

    @Transactional
    suspend fun cancelOrder(orderId: String, request: CancelOrderRequest): OrderResponse {
        val order = findOrderOrThrow(orderId)

        check(order.status in CANCELLABLE_STATUSES) {
            "Cannot cancel order in status ${order.status}. orderId=$orderId"
        }

        val updated = order.copy(
            status = OrderStatus.CANCELLING,
            cancelledReason = request.reason,
            cancellationStatus = CancellationState(
                initiatedAt = Instant.now(),
                compensationStatus = CompensationStatus.IN_PROGRESS
            )
        )

        val saved = orderRepository.save(updated)

        // 취소 Saga 시작: 배송 취소 명령 발행
        // CANCEL → delivery-service → reply 수신 후 환불 → 재고 복구
        outboxRepository.save(
            OutboxEvent.of(
                aggregateId = orderId,
                topic = DELIVERY_COMMAND_TOPIC,
                message = deliveryCommand {
                    sagaId = orderId
                    type = DeliveryCommandType.CANCEL
                    this.orderId = orderId
                    userId = order.userId
                    receiverName = order.receiverName
                    receiverPhone = order.receiverPhone
                    deliveryAddress = order.deliveryAddress
                }
            )
        )

        log.info("[Order] Cancellation initiated - orderId={}", orderId)
        return OrderResponse.from(saved)
    }

    // ──────────────────────────────────────────
    // 반품 신청
    // 도메인 불변식: DELIVERED 상태만 반품 가능
    // ──────────────────────────────────────────

    @Transactional
    suspend fun requestReturn(orderId: String, request: ReturnOrderRequest): OrderResponse {
        val order = findOrderOrThrow(orderId)

        check(order.status == OrderStatus.DELIVERED) {
            "Cannot request return for order in status ${order.status}. orderId=$orderId"
        }

        val updated = order.copy(
            status = OrderStatus.RETURN_REQUESTED,
            returnReason = request.reason,
            returnStatus = ReturnState(
                initiatedAt = Instant.now(),
                compensationStatus = CompensationStatus.IN_PROGRESS
            )
        )

        val saved = orderRepository.save(updated)

        // 반품 Saga 시작: 배송 반품 픽업 명령 발행
        // RETURN_PICKUP → delivery-service(픽업+검수) → reply → 환불 → 재고 복구
        outboxRepository.save(
            OutboxEvent.of(
                aggregateId = orderId,
                topic = DELIVERY_COMMAND_TOPIC,
                message = deliveryCommand {
                    sagaId = orderId
                    type = DeliveryCommandType.RETURN_PICKUP
                    this.orderId = orderId
                    userId = order.userId
                    receiverName = order.receiverName
                    receiverPhone = order.receiverPhone
                    deliveryAddress = order.deliveryAddress
                }
            )
        )

        log.info("[Order] Return requested - orderId={}", orderId)
        return OrderResponse.from(saved)
    }

    // ──────────────────────────────────────────
    // 내부 헬퍼
    // ──────────────────────────────────────────

    suspend fun findOrderOrThrow(orderId: String): Orders =
        orderRepository.findById(orderId)
            ?: throw NoSuchElementException("Order not found. orderId=$orderId")
}
