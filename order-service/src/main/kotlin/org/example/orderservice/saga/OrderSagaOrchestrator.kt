package org.example.orderservice.saga

import common.document.order.CompensationStatus
import common.document.order.OrderStatus
import common.saga.SagaStep
import common.saga.command.DeliveryCommandType
import common.saga.command.InventoryCommandType
import common.saga.command.PaymentCommandType
import common.saga.command.deliveryCommand
import common.saga.command.inventoryCommand
import common.saga.command.inventoryCommandItem
import common.saga.command.paymentCommand
import common.saga.reply.DeliveryReply
import common.saga.reply.InventoryReply
import common.saga.reply.PaymentReply
import org.example.orderservice.document.Orders
import org.example.orderservice.document.OutboxEvent
import org.example.orderservice.repository.OrderRepository
import org.example.orderservice.repository.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class OrderSagaOrchestrator(
    private val orderRepository: OrderRepository,
    private val outboxRepository: OutboxRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val INVENTORY_COMMAND_TOPIC = "inventory-command"
        const val INVENTORY_REPLY_TOPIC   = "inventory-reply"
        const val PAYMENT_COMMAND_TOPIC   = "payment-command"
        const val PAYMENT_REPLY_TOPIC     = "payment-reply"
        const val DELIVERY_COMMAND_TOPIC  = "delivery-command"
        const val DELIVERY_REPLY_TOPIC    = "delivery-reply"

        /** 종료된 Saga 상태 — 이후 Reply는 모두 skip */
        private val TERMINAL_STATUSES = setOf(
            OrderStatus.CANCELLED,
            OrderStatus.CANCELLATION_FAILED,
            OrderStatus.RETURN_COMPLETED,
            OrderStatus.RETURN_FAILED
        )
    }

    // ══════════════════════════════════════════════════════════════
    // Inventory Reply 핸들러
    //
    // 흐름 판별 (order.status 기준):
    //   CANCELLING          → 취소 Saga: 재고 복구
    //   RETURN_IN_PROGRESS  → 반품 Saga: 재고 복구
    //   else + INVENTORY_RESERVE → 생성 Saga Step 1
    //   else + COMPENSATING      → 생성 Saga 보상: 재고 해제
    // ══════════════════════════════════════════════════════════════

    @Transactional
    @KafkaListener(topics = [INVENTORY_REPLY_TOPIC], groupId = "order-saga")
    suspend fun onInventoryReply(reply: InventoryReply, ack: Acknowledgment) {
        // OptimisticLockingFailureException: @Version 충돌 → Kafka 재시도에 맡김 (ack 하지 않음)
        // 그 외 예외: DLT로 라우팅 → ack 하여 원본 파티션에서 제거
        var shouldAck = true
        try {
            log.info("[Saga] InventoryReply - sagaId={}, success={}", reply.sagaId, reply.success)
            val order = findOrder(reply.sagaId) ?: return
            if (isTerminated(order)) {
                log.warn("[Saga] Late inventory reply ignored - sagaId={}, status={}", reply.sagaId, order.status)
                return
            }
            when (order.status) {
                OrderStatus.CANCELLING         -> handleCancellationInventoryRestore(order, reply)
                OrderStatus.RETURN_IN_PROGRESS -> handleReturnInventoryRestore(order, reply)
                else -> when (order.sagaState?.currentStep) {
                    SagaStep.INVENTORY_RESERVE -> handleCreationInventoryReserve(order, reply)
                    SagaStep.COMPENSATING      -> handleCreationCompensationInventoryRelease(order, reply)
                    else -> log.warn("[Saga] Unexpected inventory reply - sagaId={}, step={}",
                        reply.sagaId, order.sagaState?.currentStep)
                }
            }
        } catch (e: OptimisticLockingFailureException) {
            shouldAck = false
            log.warn("[Saga] Optimistic locking conflict, Kafka will retry - sagaId={}", reply.sagaId)
            throw e
        } finally {
            if (shouldAck) ack.acknowledge()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Payment Reply 핸들러
    //
    //   CANCELLING          → 취소 Saga: 환불 결과
    //   RETURN_IN_PROGRESS  → 반품 Saga: 환불 결과
    //   else + PAYMENT_PROCESS  → 생성 Saga Step 2
    //   else + COMPENSATING     → 생성 Saga 보상: 환불 → 재고 해제
    // ══════════════════════════════════════════════════════════════

    @Transactional
    @KafkaListener(topics = [PAYMENT_REPLY_TOPIC], groupId = "order-saga")
    suspend fun onPaymentReply(reply: PaymentReply, ack: Acknowledgment) {
        var shouldAck = true
        try {
            log.info("[Saga] PaymentReply - sagaId={}, success={}", reply.sagaId, reply.success)
            val order = findOrder(reply.sagaId) ?: return
            if (isTerminated(order)) {
                log.warn("[Saga] Late payment reply ignored - sagaId={}, status={}", reply.sagaId, order.status)
                return
            }
            when (order.status) {
                OrderStatus.CANCELLING         -> handleCancellationPaymentRefund(order, reply)
                OrderStatus.RETURN_IN_PROGRESS -> handleReturnPaymentRefund(order, reply)
                else -> when (order.sagaState?.currentStep) {
                    SagaStep.PAYMENT_PROCESS -> handleCreationPaymentProcess(order, reply)
                    SagaStep.COMPENSATING    -> handleCreationCompensationPaymentRefund(order, reply)
                    else -> log.warn("[Saga] Unexpected payment reply - sagaId={}, step={}",
                        reply.sagaId, order.sagaState?.currentStep)
                }
            }
        } catch (e: OptimisticLockingFailureException) {
            shouldAck = false
            log.warn("[Saga] Optimistic locking conflict, Kafka will retry - sagaId={}", reply.sagaId)
            throw e
        } finally {
            if (shouldAck) ack.acknowledge()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Delivery Reply 핸들러
    //
    //   CANCELLING       → 취소 Saga: 배송 취소
    //   RETURN_REQUESTED → 반품 Saga: 픽업 스케줄
    //   else + DELIVERY_CREATE → 생성 Saga Step 3
    // ══════════════════════════════════════════════════════════════

    @Transactional
    @KafkaListener(topics = [DELIVERY_REPLY_TOPIC], groupId = "order-saga")
    suspend fun onDeliveryReply(reply: DeliveryReply, ack: Acknowledgment) {
        var shouldAck = true
        try {
            log.info("[Saga] DeliveryReply - sagaId={}, success={}", reply.sagaId, reply.success)
            val order = findOrder(reply.sagaId) ?: return
            if (isTerminated(order)) {
                log.warn("[Saga] Late delivery reply ignored - sagaId={}, status={}", reply.sagaId, order.status)
                return
            }
            when (order.status) {
                OrderStatus.CANCELLING       -> handleCancellationDeliveryCancel(order, reply)
                OrderStatus.RETURN_REQUESTED -> handleReturnPickupScheduled(order, reply)
                else -> when (order.sagaState?.currentStep) {
                    SagaStep.DELIVERY_CREATE -> handleCreationDeliveryCreate(order, reply)
                    else -> log.warn("[Saga] Unexpected delivery reply - sagaId={}, step={}",
                        reply.sagaId, order.sagaState?.currentStep)
                }
            }
        } catch (e: OptimisticLockingFailureException) {
            shouldAck = false
            log.warn("[Saga] Optimistic locking conflict, Kafka will retry - sagaId={}", reply.sagaId)
            throw e
        } finally {
            if (shouldAck) ack.acknowledge()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 생성 Saga 핸들러
    // ══════════════════════════════════════════════════════════════

    private suspend fun handleCreationInventoryReserve(order: Orders, reply: InventoryReply) {
        // 멱등성 체크: 이미 처리된 스텝이면 skip
        if (order.sagaState?.inventoryReserved == true) return

        if (reply.success) {
            orderRepository.save(
                order.copy(sagaState = order.sagaState?.copy(
                    inventoryReserved = true,
                    currentStep = SagaStep.PAYMENT_PROCESS
                ))
            )
            outboxRepository.save(OutboxEvent.of(
                aggregateId = order.orderId,
                topic = PAYMENT_COMMAND_TOPIC,
                message = paymentCommand {
                    sagaId = reply.sagaId
                    type = PaymentCommandType.PROCESS
                    orderId = order.orderId
                    userId = order.userId
                    amount = order.totalAmount.toPlainString()
                }
            ))
            log.info("[CreationSaga] Inventory reserved -> payment - sagaId={}", reply.sagaId)
        } else {
            // 재고 예약 실패: 보상 없이 즉시 실패 (예약 안 됐으므로 해제 불필요)
            failCreationSaga(order, reply.failureReason ?: "재고 예약 실패")
        }
    }

    private suspend fun handleCreationPaymentProcess(order: Orders, reply: PaymentReply) {
        if (order.sagaState?.paymentProcessed == true) return

        if (reply.success) {
            orderRepository.save(
                order.copy(
                    status = OrderStatus.PAYMENT_COMPLETED,
                    paymentTxId = if (reply.hasPaymentId()) reply.paymentId else null,
                    sagaState = order.sagaState?.copy(
                        paymentProcessed = true,
                        currentStep = SagaStep.DELIVERY_CREATE
                    )
                )
            )
            outboxRepository.save(OutboxEvent.of(
                aggregateId = order.orderId,
                topic = DELIVERY_COMMAND_TOPIC,
                message = deliveryCommand {
                    sagaId = reply.sagaId
                    type = DeliveryCommandType.CREATE
                    orderId = order.orderId
                    userId = order.userId
                    receiverName = order.receiverName
                    receiverPhone = order.receiverPhone
                    deliveryAddress = order.deliveryAddress
                }
            ))
            log.info("[CreationSaga] Payment processed -> delivery - sagaId={}", reply.sagaId)
        } else {
            // 결제 실패: 재고 해제 보상
            compensateCreationInventory(order, reply.failureReason ?: "결제 처리 실패")
        }
    }

    private suspend fun handleCreationDeliveryCreate(order: Orders, reply: DeliveryReply) {
        if (order.sagaState?.deliveryCreated == true) return

        if (reply.success) {
            orderRepository.save(
                order.copy(
                    status = OrderStatus.PREPARING,
                    deliveryId = if (reply.hasDeliveryId()) reply.deliveryId else null,
                    sagaState = order.sagaState?.copy(
                        deliveryCreated = true,
                        currentStep = SagaStep.COMPLETED
                    )
                )
            )
            log.info("[CreationSaga] Completed - sagaId={}", reply.sagaId)
        } else {
            // 배송 실패: 결제 환불 → 재고 해제 순 보상
            compensateCreationPayment(order, reply.failureReason ?: "배송 생성 실패")
        }
    }

    private suspend fun compensateCreationPayment(order: Orders, reason: String) {
        orderRepository.save(
            order.copy(sagaState = order.sagaState?.copy(
                currentStep = SagaStep.COMPENSATING,
                failureReason = reason
            ))
        )
        outboxRepository.save(OutboxEvent.of(
            aggregateId = order.orderId,
            topic = PAYMENT_COMMAND_TOPIC,
            message = paymentCommand {
                sagaId = order.orderId
                type = PaymentCommandType.REFUND
                orderId = order.orderId
                userId = order.userId
                amount = order.totalAmount.toPlainString()
            }
        ))
        log.warn("[CreationSaga] Compensating payment - sagaId={}, reason={}", order.orderId, reason)
    }

    private suspend fun handleCreationCompensationPaymentRefund(order: Orders, reply: PaymentReply) {
        // 환불 성공/실패 불문하고 재고 해제 진행 (Best-Effort 보상)
        if (!reply.success) {
            log.warn("[CreationSaga] Payment refund failed during compensation - sagaId={}", reply.sagaId)
        }
        compensateCreationInventory(order, order.sagaState?.failureReason ?: "보상 진행 중")
    }

    private suspend fun compensateCreationInventory(order: Orders, reason: String) {
        orderRepository.save(
            order.copy(sagaState = order.sagaState?.copy(
                currentStep = SagaStep.COMPENSATING,
                failureReason = reason
            ))
        )
        outboxRepository.save(OutboxEvent.of(
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
        ))
        log.warn("[CreationSaga] Compensating inventory - sagaId={}", order.orderId)
    }

    private suspend fun handleCreationCompensationInventoryRelease(order: Orders, reply: InventoryReply) {
        if (!reply.success) {
            log.error("[CreationSaga] Inventory release failed during compensation - sagaId={}", reply.sagaId)
        }
        failCreationSaga(order, order.sagaState?.failureReason ?: "Saga 보상 완료")
    }

    private suspend fun failCreationSaga(order: Orders, reason: String) {
        orderRepository.save(
            order.copy(
                status = OrderStatus.CANCELLED,
                sagaState = order.sagaState?.copy(
                    currentStep = SagaStep.FAILED,
                    failureReason = reason
                )
            )
        )
        log.warn("[CreationSaga] Failed - sagaId={}, reason={}", order.orderId, reason)
    }

    // ══════════════════════════════════════════════════════════════
    // 취소 Saga 핸들러
    // 순서: CANCEL delivery → REFUND payment → RELEASE inventory → CANCELLED
    // Best-Effort: 배송 취소 실패 시 CANCELLATION_FAILED (환불 시도하지 않음)
    // ══════════════════════════════════════════════════════════════

    private suspend fun handleCancellationDeliveryCancel(order: Orders, reply: DeliveryReply) {
        if (order.cancellationStatus?.deliveryCancelled == true) return

        if (reply.success) {
            orderRepository.save(
                order.copy(cancellationStatus = order.cancellationStatus?.copy(
                    deliveryCancelled = true,
                    deliveryCancelledAt = Instant.now()
                ))
            )
            outboxRepository.save(OutboxEvent.of(
                aggregateId = order.orderId,
                topic = PAYMENT_COMMAND_TOPIC,
                message = paymentCommand {
                    sagaId = order.orderId
                    type = PaymentCommandType.REFUND
                    orderId = order.orderId
                    userId = order.userId
                    amount = order.totalAmount.toPlainString()
                }
            ))
            log.info("[CancelSaga] Delivery cancelled -> refund - sagaId={}", order.orderId)
        } else {
            failCancellationSaga(order, reply.failureReason ?: "배송 취소 실패",
                deliveryError = reply.failureReason)
        }
    }

    private suspend fun handleCancellationPaymentRefund(order: Orders, reply: PaymentReply) {
        if (order.cancellationStatus?.paymentRefunded == true) return

        if (reply.success) {
            orderRepository.save(
                order.copy(cancellationStatus = order.cancellationStatus?.copy(
                    paymentRefunded = true,
                    paymentRefundedAt = Instant.now(),
                    paymentRefundTxId = if (reply.hasPaymentId()) reply.paymentId else null
                ))
            )
            outboxRepository.save(OutboxEvent.of(
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
            ))
            log.info("[CancelSaga] Payment refunded -> restore inventory - sagaId={}", order.orderId)
        } else {
            failCancellationSaga(order, reply.failureReason ?: "환불 실패",
                paymentError = reply.failureReason)
        }
    }

    private suspend fun handleCancellationInventoryRestore(order: Orders, reply: InventoryReply) {
        if (order.cancellationStatus?.inventoryRestored == true) return

        val now = Instant.now()
        if (reply.success) {
            orderRepository.save(
                order.copy(
                    status = OrderStatus.CANCELLED,
                    cancelledAt = now,
                    cancellationStatus = order.cancellationStatus?.copy(
                        inventoryRestored = true,
                        inventoryRestoredAt = now,
                        completedAt = now,
                        compensationStatus = CompensationStatus.COMPLETED
                    )
                )
            )
            log.info("[CancelSaga] Completed - sagaId={}", order.orderId)
        } else {
            failCancellationSaga(order, reply.failureReason ?: "재고 복구 실패",
                inventoryError = reply.failureReason)
        }
    }

    private suspend fun failCancellationSaga(
        order: Orders,
        reason: String,
        deliveryError: String? = null,
        paymentError: String? = null,
        inventoryError: String? = null
    ) {
        orderRepository.save(
            order.copy(
                status = OrderStatus.CANCELLATION_FAILED,
                cancellationStatus = order.cancellationStatus?.let { cs ->
                    cs.copy(
                        deliveryCancellationError = deliveryError ?: cs.deliveryCancellationError,
                        paymentRefundError = paymentError ?: cs.paymentRefundError,
                        inventoryRestorationError = inventoryError ?: cs.inventoryRestorationError,
                        completedAt = Instant.now(),
                        compensationStatus = CompensationStatus.FAILED
                    )
                }
            )
        )
        log.error("[CancelSaga] Failed - sagaId={}, reason={}", order.orderId, reason)
    }

    // ══════════════════════════════════════════════════════════════
    // 반품 Saga 핸들러
    // 순서: RETURN_PICKUP → REFUND payment → RELEASE inventory → RETURN_COMPLETED
    // ══════════════════════════════════════════════════════════════

    private suspend fun handleReturnPickupScheduled(order: Orders, reply: DeliveryReply) {
        if (order.returnStatus?.pickupScheduled == true) return

        val now = Instant.now()
        if (reply.success) {
            orderRepository.save(
                order.copy(
                    status = OrderStatus.RETURN_IN_PROGRESS,
                    returnStatus = order.returnStatus?.copy(
                        pickupScheduled = true,
                        pickupScheduledAt = now,
                        // delivery-service가 픽업+검수를 한 번의 reply로 처리
                        itemsReceived = true,
                        itemsReceivedAt = now
                    )
                )
            )
            outboxRepository.save(OutboxEvent.of(
                aggregateId = order.orderId,
                topic = PAYMENT_COMMAND_TOPIC,
                message = paymentCommand {
                    sagaId = order.orderId
                    type = PaymentCommandType.REFUND
                    orderId = order.orderId
                    userId = order.userId
                    amount = order.totalAmount.toPlainString()
                }
            ))
            log.info("[ReturnSaga] Pickup scheduled -> refund - sagaId={}", order.orderId)
        } else {
            failReturnSaga(order, reply.failureReason ?: "반품 픽업 실패",
                pickupError = reply.failureReason)
        }
    }

    private suspend fun handleReturnPaymentRefund(order: Orders, reply: PaymentReply) {
        if (order.returnStatus?.paymentRefunded == true) return

        if (reply.success) {
            orderRepository.save(
                order.copy(returnStatus = order.returnStatus?.copy(
                    paymentRefunded = true,
                    paymentRefundedAt = Instant.now(),
                    paymentRefundTxId = if (reply.hasPaymentId()) reply.paymentId else null
                ))
            )
            outboxRepository.save(OutboxEvent.of(
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
            ))
            log.info("[ReturnSaga] Payment refunded -> restore inventory - sagaId={}", order.orderId)
        } else {
            failReturnSaga(order, reply.failureReason ?: "반품 환불 실패",
                paymentError = reply.failureReason)
        }
    }

    private suspend fun handleReturnInventoryRestore(order: Orders, reply: InventoryReply) {
        if (order.returnStatus?.inventoryRestored == true) return

        val now = Instant.now()
        if (reply.success) {
            orderRepository.save(
                order.copy(
                    status = OrderStatus.RETURN_COMPLETED,
                    returnedAt = now,
                    returnStatus = order.returnStatus?.copy(
                        inventoryRestored = true,
                        inventoryRestoredAt = now,
                        completedAt = now,
                        compensationStatus = CompensationStatus.COMPLETED
                    )
                )
            )
            log.info("[ReturnSaga] Completed - sagaId={}", order.orderId)
        } else {
            failReturnSaga(order, reply.failureReason ?: "반품 재고 복구 실패",
                inventoryError = reply.failureReason)
        }
    }

    private suspend fun failReturnSaga(
        order: Orders,
        reason: String,
        pickupError: String? = null,
        paymentError: String? = null,
        inventoryError: String? = null
    ) {
        orderRepository.save(
            order.copy(
                status = OrderStatus.RETURN_FAILED,
                returnStatus = order.returnStatus?.let { rs ->
                    rs.copy(
                        pickupError = pickupError ?: rs.pickupError,
                        paymentRefundError = paymentError ?: rs.paymentRefundError,
                        inventoryRestorationError = inventoryError ?: rs.inventoryRestorationError,
                        completedAt = Instant.now(),
                        compensationStatus = CompensationStatus.FAILED
                    )
                }
            )
        )
        log.error("[ReturnSaga] Failed - sagaId={}, reason={}", order.orderId, reason)
    }

    // ══════════════════════════════════════════════════════════════
    // 공통 헬퍼
    // ══════════════════════════════════════════════════════════════

    private suspend fun findOrder(sagaId: String): Orders? {
        val order = orderRepository.findById(sagaId)
        if (order == null) log.error("[Saga] Order not found - sagaId={}", sagaId)
        return order
    }

    private fun isTerminated(order: Orders): Boolean =
        order.status in TERMINAL_STATUSES ||
        order.sagaState?.currentStep in setOf(SagaStep.COMPLETED, SagaStep.FAILED)
}
