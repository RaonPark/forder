package org.example.orderservice.saga

import common.document.order.OrderStatus
import common.saga.OrderSagaState
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
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OrderSagaOrchestrator(
    private val orderRepository: OrderRepository,
    private val outboxRepository: OutboxRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // ──────────────────────────────────────────
    // Saga 시작
    // ──────────────────────────────────────────

    @Transactional
    suspend fun startSaga(order: Orders) {
        val sagaState = OrderSagaState(
            sagaId = order.orderId,
            currentStep = SagaStep.INVENTORY_RESERVE
        )
        orderRepository.save(order.copy(sagaState = sagaState))

        outboxRepository.save(
            OutboxEvent.of(
                aggregateId = order.orderId,
                topic = INVENTORY_COMMAND_TOPIC,
                message = inventoryCommand {
                    sagaId = order.orderId
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
        log.info("[Saga] Started - sagaId={}", order.orderId)
    }

    // ──────────────────────────────────────────
    // Step 1: 재고 예약 결과
    // ──────────────────────────────────────────

    @Transactional
    @KafkaListener(topics = [INVENTORY_REPLY_TOPIC], groupId = "order-saga")
    suspend fun onInventoryReply(reply: InventoryReply, ack: Acknowledgment) {
        log.info("[Saga] InventoryReply - sagaId={}, success={}", reply.sagaId, reply.success)

        val order = orderRepository.findById(reply.sagaId) ?: run {
            log.error("[Saga] Order not found - sagaId={}", reply.sagaId)
            ack.acknowledge()
            return
        }

        when (order.sagaState?.currentStep) {
            SagaStep.INVENTORY_RESERVE -> {
                if (reply.success) {
                    orderRepository.save(
                        order.copy(
                            sagaState = order.sagaState?.copy(
                                inventoryReserved = true,
                                currentStep = SagaStep.PAYMENT_PROCESS
                            )
                        )
                    )
                    outboxRepository.save(
                        OutboxEvent.of(
                            aggregateId = reply.sagaId,
                            topic = PAYMENT_COMMAND_TOPIC,
                            message = paymentCommand {
                                sagaId = reply.sagaId
                                type = PaymentCommandType.PROCESS
                                orderId = order.orderId
                                userId = order.userId
                                amount = order.totalAmount.toPlainString()
                            }
                        )
                    )
                } else {
                    failSaga(order, if (reply.hasFailureReason()) reply.failureReason else "재고 예약 실패")
                }
            }
            SagaStep.COMPENSATING -> {
                // 보상(RELEASE) 완료 → 최종 실패 처리
                failSaga(order, order.sagaState?.failureReason ?: "Saga 보상 완료")
            }
            else -> log.warn("[Saga] Unexpected step on inventory reply - sagaId={}, step={}", reply.sagaId, order.sagaState?.currentStep)
        }

        ack.acknowledge()
    }

    // ──────────────────────────────────────────
    // Step 2: 결제 처리 결과
    // ──────────────────────────────────────────

    @Transactional
    @KafkaListener(topics = [PAYMENT_REPLY_TOPIC], groupId = "order-saga")
    suspend fun onPaymentReply(reply: PaymentReply, ack: Acknowledgment) {
        log.info("[Saga] PaymentReply - sagaId={}, success={}", reply.sagaId, reply.success)

        val order = orderRepository.findById(reply.sagaId) ?: run {
            log.error("[Saga] Order not found - sagaId={}", reply.sagaId)
            ack.acknowledge()
            return
        }

        when (order.sagaState?.currentStep) {
            SagaStep.PAYMENT_PROCESS -> {
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
                    outboxRepository.save(
                        OutboxEvent.of(
                            aggregateId = reply.sagaId,
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
                        )
                    )
                } else {
                    compensateInventory(order, if (reply.hasFailureReason()) reply.failureReason else "결제 실패")
                }
            }
            SagaStep.COMPENSATING -> {
                // 보상(REFUND) 완료 → 재고도 해제
                compensateInventory(order, order.sagaState?.failureReason ?: "Saga 보상 완료")
            }
            else -> log.warn("[Saga] Unexpected step on payment reply - sagaId={}, step={}", reply.sagaId, order.sagaState?.currentStep)
        }

        ack.acknowledge()
    }

    // ──────────────────────────────────────────
    // Step 3: 배송 생성 결과
    // ──────────────────────────────────────────

    @Transactional
    @KafkaListener(topics = [DELIVERY_REPLY_TOPIC], groupId = "order-saga")
    suspend fun onDeliveryReply(reply: DeliveryReply, ack: Acknowledgment) {
        log.info("[Saga] DeliveryReply - sagaId={}, success={}", reply.sagaId, reply.success)

        val order = orderRepository.findById(reply.sagaId) ?: run {
            log.error("[Saga] Order not found - sagaId={}", reply.sagaId)
            ack.acknowledge()
            return
        }

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
            log.info("[Saga] Completed - sagaId={}", reply.sagaId)
        } else {
            compensatePayment(order, if (reply.hasFailureReason()) reply.failureReason else "배송 생성 실패")
        }

        ack.acknowledge()
    }

    // ──────────────────────────────────────────
    // 보상 트랜잭션
    // ──────────────────────────────────────────

    private suspend fun compensateInventory(order: Orders, reason: String) {
        orderRepository.save(
            order.copy(
                sagaState = order.sagaState?.copy(
                    currentStep = SagaStep.COMPENSATING,
                    failureReason = reason
                )
            )
        )
        outboxRepository.save(
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
        )
    }

    private suspend fun compensatePayment(order: Orders, reason: String) {
        orderRepository.save(
            order.copy(
                sagaState = order.sagaState?.copy(
                    currentStep = SagaStep.COMPENSATING,
                    failureReason = reason
                )
            )
        )
        outboxRepository.save(
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
        )
    }

    private suspend fun failSaga(order: Orders, reason: String) {
        orderRepository.save(
            order.copy(
                status = OrderStatus.CANCELLED,
                sagaState = order.sagaState?.copy(
                    currentStep = SagaStep.FAILED,
                    failureReason = reason
                )
            )
        )
        log.warn("[Saga] Failed - sagaId={}, reason={}", order.orderId, reason)
    }

    companion object {
        const val INVENTORY_COMMAND_TOPIC = "inventory-command"
        const val INVENTORY_REPLY_TOPIC   = "inventory-reply"
        const val PAYMENT_COMMAND_TOPIC   = "payment-command"
        const val PAYMENT_REPLY_TOPIC     = "payment-reply"
        const val DELIVERY_COMMAND_TOPIC  = "delivery-command"
        const val DELIVERY_REPLY_TOPIC    = "delivery-reply"
    }
}
