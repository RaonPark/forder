package org.example.orderservice.saga

import common.document.order.CompensationStatus
import common.document.order.OrderStatus
import common.saga.SagaStep
import common.saga.command.InventoryCommandType
import common.saga.command.PaymentCommandType
import common.saga.command.inventoryCommand
import common.saga.command.inventoryCommandItem
import common.saga.command.paymentCommand
import org.example.orderservice.document.Orders
import org.example.orderservice.document.OutboxEvent
import org.example.orderservice.dto.TimeoutOutcome
import org.example.orderservice.repository.OrderRepository
import org.example.orderservice.repository.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class SagaTimeoutHandler(
    private val orderRepository: OrderRepository,
    private val outboxRepository: OutboxRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val PAYMENT_COMMAND_TOPIC  = "payment-command"
        private const val INVENTORY_COMMAND_TOPIC = "inventory-command"
    }

    /**
     * 단일 주문의 Saga 타임아웃을 트랜잭션으로 처리한다.
     *
     * 핵심 원칙:
     * 1. 주문을 terminal 상태로 먼저 저장
     *    → Orchestrator 의 isTerminated() 가 이후 late reply 를 무시
     * 2. 그 다음 보상 outbox 이벤트를 발행 (best-effort)
     *    → 고객 환불·재고 복구 보장
     * 3. 두 저장이 같은 트랜잭션 → 부분 실패 없음
     */
    @Transactional
    suspend fun handle(order: Orders): TimeoutOutcome {
        log.warn("[Timeout] Processing stuck order - orderId={}, status={}, updatedAt={}",
            order.orderId, order.status, order.updatedAt)
        return when (order.status) {
            OrderStatus.PENDING                                    -> handleCreationTimeout(order)
            OrderStatus.CANCELLING                                 -> handleCancellationTimeout(order)
            OrderStatus.RETURN_REQUESTED, OrderStatus.RETURN_IN_PROGRESS -> handleReturnTimeout(order)
            else -> TimeoutOutcome.SKIPPED
        }
    }

    // ── 생성 Saga 타임아웃 ────────────────────────────────────────────

    private suspend fun handleCreationTimeout(order: Orders): TimeoutOutcome {
        val sagaState = order.sagaState

        // 보상 자체가 멈춘 경우: 수동 개입 없이는 해결 불가
        if (sagaState?.currentStep == SagaStep.COMPENSATING) {
            log.error("[Timeout] Creation saga stuck in COMPENSATING - manual intervention required - orderId={}", order.orderId)
            return TimeoutOutcome.SKIPPED
        }

        // Step 1: terminal 상태로 먼저 저장 → 이후 late reply 는 Orchestrator 가 무시
        orderRepository.save(order.copy(
            status = OrderStatus.CANCELLED,
            sagaState = sagaState?.copy(
                currentStep = SagaStep.FAILED,
                failureReason = "Saga timeout"
            )
        ))

        // Step 2: 진행된 단계에 따라 best-effort 보상
        when {
            sagaState?.paymentProcessed == true -> {
                // 결제까지 완료 → 환불 + 재고 해제
                outboxRepository.save(buildPaymentRefundEvent(order))
                outboxRepository.save(buildInventoryReleaseEvent(order))
                log.warn("[Timeout] Creation timeout after payment - refund+release queued - orderId={}", order.orderId)
            }
            sagaState?.inventoryReserved == true -> {
                // 재고 예약만 완료 → 재고 해제
                outboxRepository.save(buildInventoryReleaseEvent(order))
                log.warn("[Timeout] Creation timeout after inventory reserve - release queued - orderId={}", order.orderId)
            }
            else -> {
                // 아무 단계도 완료되지 않음 → 보상 불필요
                log.warn("[Timeout] Creation timeout before any step completed - orderId={}", order.orderId)
            }
        }

        return TimeoutOutcome.CANCELLED
    }

    // ── 취소 Saga 타임아웃 ────────────────────────────────────────────

    private suspend fun handleCancellationTimeout(order: Orders): TimeoutOutcome {
        val cs = order.cancellationStatus

        // Step 1: CANCELLATION_FAILED 로 terminal 처리
        orderRepository.save(order.copy(
            status = OrderStatus.CANCELLATION_FAILED,
            cancellationStatus = cs?.copy(
                completedAt = Instant.now(),
                compensationStatus = CompensationStatus.FAILED
            )
        ))

        // Step 2: 고객 돈이 걸린 구간이면 best-effort 보상
        when {
            cs?.deliveryCancelled == true && !cs.paymentRefunded -> {
                // 배송은 취소됐는데 환불이 안 됨 → 반드시 환불 시도
                outboxRepository.save(buildPaymentRefundEvent(order))
                log.error("[Timeout] Cancellation timeout - delivery cancelled but refund missing - best-effort refund queued - orderId={}", order.orderId)
            }
            cs?.paymentRefunded == true && !cs.inventoryRestored -> {
                // 환불은 됐는데 재고 복구가 안 됨
                outboxRepository.save(buildInventoryReleaseEvent(order))
                log.error("[Timeout] Cancellation timeout - refund done but inventory restore missing - release queued - orderId={}", order.orderId)
            }
            else ->
                log.error("[Timeout] Cancellation saga timeout - manual intervention required - orderId={}", order.orderId)
        }

        return TimeoutOutcome.CANCELLATION_FAILED
    }

    // ── 반품 Saga 타임아웃 ────────────────────────────────────────────

    private suspend fun handleReturnTimeout(order: Orders): TimeoutOutcome {
        val rs = order.returnStatus

        // Step 1: RETURN_FAILED 로 terminal 처리
        orderRepository.save(order.copy(
            status = OrderStatus.RETURN_FAILED,
            returnStatus = rs?.copy(
                completedAt = Instant.now(),
                compensationStatus = CompensationStatus.FAILED
            )
        ))

        // Step 2: 상품 수령 후 환불이 안 된 구간 → 반드시 환불 시도
        when {
            rs?.itemsReceived == true && !rs.paymentRefunded -> {
                outboxRepository.save(buildPaymentRefundEvent(order))
                log.error("[Timeout] Return timeout - items received but refund missing - best-effort refund queued - orderId={}", order.orderId)
            }
            rs?.paymentRefunded == true && !rs.inventoryRestored -> {
                outboxRepository.save(buildInventoryReleaseEvent(order))
                log.error("[Timeout] Return timeout - refund done but inventory restore missing - release queued - orderId={}", order.orderId)
            }
            else ->
                log.error("[Timeout] Return saga timeout - manual intervention required - orderId={}", order.orderId)
        }

        return TimeoutOutcome.RETURN_FAILED
    }

    // ── Outbox 이벤트 빌더 ────────────────────────────────────────────

    private fun buildPaymentRefundEvent(order: Orders): OutboxEvent =
        OutboxEvent.of(
            aggregateId = order.orderId,
            topic = PAYMENT_COMMAND_TOPIC,
            message = paymentCommand {
                sagaId = order.orderId
                type = PaymentCommandType.REFUND
                orderId = order.orderId
                userId = order.userId
                amount = order.totalAmount.toPlainString()
            }
        )

    private fun buildInventoryReleaseEvent(order: Orders): OutboxEvent =
        OutboxEvent.of(
            aggregateId = order.orderId,
            topic = INVENTORY_COMMAND_TOPIC,
            message = inventoryCommand {
                sagaId = order.orderId
                type = InventoryCommandType.RELEASE
                items += order.items.map { item ->
                    inventoryCommandItem {
                        productId = item.productId
                        quantity = item.quantity
                    }
                }
            }
        )
}
