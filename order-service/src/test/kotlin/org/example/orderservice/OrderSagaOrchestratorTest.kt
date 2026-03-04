package org.example.orderservice

import common.document.order.CancellationState
import common.document.order.CompensationStatus
import common.document.order.OrderStatus
import common.document.order.ReturnState
import common.saga.OrderSagaState
import common.saga.SagaStep
import common.saga.reply.DeliveryReply
import common.saga.reply.InventoryReply
import common.saga.reply.PaymentReply
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.clearAllMocks
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.springframework.dao.OptimisticLockingFailureException
import org.example.orderservice.document.OrderItem
import org.example.orderservice.document.Orders
import org.example.orderservice.document.OutboxEvent
import org.example.orderservice.repository.OrderRepository
import org.example.orderservice.repository.OutboxRepository
import org.example.orderservice.saga.OrderSagaOrchestrator
import org.springframework.kafka.support.Acknowledgment
import java.math.BigDecimal
import java.time.Instant

class OrderSagaOrchestratorTest : BehaviorSpec({

    isolationMode = IsolationMode.InstancePerTest

    afterEach { clearAllMocks() }

    val orderRepository = mockk<OrderRepository>()
    val outboxRepository = mockk<OutboxRepository>()
    val ack = mockk<Acknowledgment>(relaxed = true)
    val orchestrator = OrderSagaOrchestrator(orderRepository, outboxRepository)

    // ─── 픽스처 ──────────────────────────────────────────────────

    fun baseOrder(
        orderId: String = "order-001",
        status: OrderStatus = OrderStatus.PENDING,
        sagaState: OrderSagaState? = OrderSagaState(sagaId = "order-001"),
        cancellationStatus: CancellationState? = null,
        returnStatus: ReturnState? = null
    ) = Orders(
        orderId = orderId,
        userId = "user-001",
        status = status,
        items = listOf(OrderItem("prod-001", 2, BigDecimal("10000"))),
        totalAmount = BigDecimal("20000"),
        deliveryAddress = "서울시 강남구",
        receiverName = "홍길동",
        receiverPhone = "010-1234-5678",
        sagaState = sagaState,
        cancellationStatus = cancellationStatus,
        returnStatus = returnStatus,
        createdAt = Instant.now()
    )

    fun inventoryReply(sagaId: String = "order-001", success: Boolean = true) =
        InventoryReply.newBuilder().setSagaId(sagaId).setSuccess(success).build()

    fun paymentReply(sagaId: String = "order-001", success: Boolean = true, paymentId: String = "pay-001") =
        PaymentReply.newBuilder().setSagaId(sagaId).setSuccess(success).setPaymentId(paymentId).build()

    fun deliveryReply(sagaId: String = "order-001", success: Boolean = true, deliveryId: String = "del-001") =
        DeliveryReply.newBuilder().setSagaId(sagaId).setSuccess(success).setDeliveryId(deliveryId).build()

    // ════════════════════════════════════════════════════════════
    // 생성 Saga — InventoryReply
    // ════════════════════════════════════════════════════════════

    given("생성 Saga: InventoryReply") {
        `when`("재고 예약 성공") {
            val order = baseOrder(sagaState = OrderSagaState("order-001", currentStep = SagaStep.INVENTORY_RESERVE))
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            orchestrator.onInventoryReply(inventoryReply(success = true), ack)

            then("sagaState가 PAYMENT_PROCESS로 전진하고 inventoryReserved=true로 기록된다") {
                savedSlot.captured.sagaState?.currentStep shouldBe SagaStep.PAYMENT_PROCESS
                savedSlot.captured.sagaState?.inventoryReserved shouldBe true
            }

            then("payment-command Outbox가 올바른 sagaId로 저장된다") {
                outboxSlot.captured.topic shouldBe "payment-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
                outboxSlot.captured.payload.isNotEmpty() shouldBe true
            }
        }

        `when`("재고 예약 실패") {
            val order = baseOrder(sagaState = OrderSagaState("order-001", currentStep = SagaStep.INVENTORY_RESERVE))
            val savedSlot = slot<Orders>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            orchestrator.onInventoryReply(inventoryReply(success = false), ack)

            then("status가 CANCELLED, sagaState가 FAILED로 변경된다") {
                savedSlot.captured.status shouldBe OrderStatus.CANCELLED
                savedSlot.captured.sagaState?.currentStep shouldBe SagaStep.FAILED
            }

            then("Outbox는 저장되지 않는다 (재고 미예약이므로 해제 불필요)") {
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }

        `when`("이미 처리된 InventoryReply (멱등성)") {
            val order = baseOrder(
                sagaState = OrderSagaState("order-001",
                    currentStep = SagaStep.PAYMENT_PROCESS,
                    inventoryReserved = true)
            )
            coEvery { orderRepository.findById("order-001") } returns order

            orchestrator.onInventoryReply(inventoryReply(success = true), ack)

            then("이미 처리됐으므로 save가 호출되지 않는다") {
                coVerify(exactly = 0) { orderRepository.save(any()) }
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 생성 Saga — PaymentReply
    // ════════════════════════════════════════════════════════════

    given("생성 Saga: PaymentReply") {
        `when`("결제 성공") {
            val order = baseOrder(
                status = OrderStatus.PENDING,
                sagaState = OrderSagaState("order-001", currentStep = SagaStep.PAYMENT_PROCESS, inventoryReserved = true)
            )
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            orchestrator.onPaymentReply(paymentReply(success = true, paymentId = "pay-001"), ack)

            then("status가 PAYMENT_COMPLETED, sagaState가 DELIVERY_CREATE로 전진한다") {
                savedSlot.captured.status shouldBe OrderStatus.PAYMENT_COMPLETED
                savedSlot.captured.sagaState?.currentStep shouldBe SagaStep.DELIVERY_CREATE
                savedSlot.captured.sagaState?.paymentProcessed shouldBe true
                savedSlot.captured.paymentTxId shouldBe "pay-001"
            }

            then("delivery-command Outbox가 올바른 sagaId로 저장된다") {
                outboxSlot.captured.topic shouldBe "delivery-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
                outboxSlot.captured.payload.isNotEmpty() shouldBe true
            }
        }

        `when`("결제 실패 → 재고 해제 보상") {
            val order = baseOrder(
                status = OrderStatus.PENDING,
                sagaState = OrderSagaState("order-001", currentStep = SagaStep.PAYMENT_PROCESS, inventoryReserved = true)
            )
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            orchestrator.onPaymentReply(paymentReply(success = false), ack)

            then("sagaState가 COMPENSATING으로 변경되고 failureReason이 기록된다") {
                savedSlot.captured.sagaState?.currentStep shouldBe SagaStep.COMPENSATING
                savedSlot.captured.sagaState?.failureReason shouldNotBe null
            }

            then("inventory-command (RELEASE) Outbox가 올바른 sagaId로 저장된다") {
                outboxSlot.captured.topic shouldBe "inventory-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
            }
        }

        `when`("이미 처리된 PaymentReply (멱등성)") {
            val order = baseOrder(
                sagaState = OrderSagaState("order-001",
                    currentStep = SagaStep.DELIVERY_CREATE,
                    inventoryReserved = true, paymentProcessed = true)
            )
            coEvery { orderRepository.findById("order-001") } returns order

            orchestrator.onPaymentReply(paymentReply(success = true), ack)

            then("이미 처리됐으므로 save가 호출되지 않는다") {
                coVerify(exactly = 0) { orderRepository.save(any()) }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 생성 Saga — DeliveryReply
    // ════════════════════════════════════════════════════════════

    given("생성 Saga: DeliveryReply") {
        `when`("배송 생성 성공") {
            val order = baseOrder(
                status = OrderStatus.PAYMENT_COMPLETED,
                sagaState = OrderSagaState("order-001", currentStep = SagaStep.DELIVERY_CREATE,
                    inventoryReserved = true, paymentProcessed = true)
            )
            val savedSlot = slot<Orders>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            orchestrator.onDeliveryReply(deliveryReply(success = true, deliveryId = "del-001"), ack)

            then("status가 PREPARING, sagaState가 COMPLETED로 변경되고 deliveryId가 기록된다") {
                savedSlot.captured.status shouldBe OrderStatus.PREPARING
                savedSlot.captured.sagaState?.currentStep shouldBe SagaStep.COMPLETED
                savedSlot.captured.sagaState?.deliveryCreated shouldBe true
                savedSlot.captured.deliveryId shouldBe "del-001"
            }

            then("추가 Outbox가 저장되지 않는다 (Saga 정상 완료)") {
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }

        `when`("배송 생성 실패 → 결제 환불 보상") {
            val order = baseOrder(
                status = OrderStatus.PAYMENT_COMPLETED,
                sagaState = OrderSagaState("order-001", currentStep = SagaStep.DELIVERY_CREATE,
                    inventoryReserved = true, paymentProcessed = true)
            )
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            orchestrator.onDeliveryReply(deliveryReply(success = false), ack)

            then("sagaState가 COMPENSATING으로 변경되고 failureReason이 기록된다") {
                savedSlot.captured.sagaState?.currentStep shouldBe SagaStep.COMPENSATING
                savedSlot.captured.sagaState?.failureReason shouldNotBe null
            }

            then("payment-command (REFUND) Outbox가 올바른 sagaId로 저장된다") {
                outboxSlot.captured.topic shouldBe "payment-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 생성 Saga 보상 체인
    // ════════════════════════════════════════════════════════════

    given("생성 Saga 보상: 결제 환불 완료 → 재고 해제") {
        `when`("환불 성공 → 재고 해제 명령 발행") {
            val order = baseOrder(
                status = OrderStatus.PAYMENT_COMPLETED,
                sagaState = OrderSagaState("order-001", currentStep = SagaStep.COMPENSATING,
                    inventoryReserved = true, paymentProcessed = true,
                    failureReason = "배송 생성 실패")
            )
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            orchestrator.onPaymentReply(paymentReply(success = true), ack)

            then("COMPENSATING 상태가 유지되고 failureReason이 보존된다") {
                savedSlot.captured.sagaState?.currentStep shouldBe SagaStep.COMPENSATING
                savedSlot.captured.sagaState?.failureReason shouldBe "배송 생성 실패"
            }

            then("inventory-command (RELEASE) Outbox가 올바른 sagaId로 저장된다") {
                outboxSlot.captured.topic shouldBe "inventory-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
                outboxSlot.captured.payload.isNotEmpty() shouldBe true
            }
        }

        `when`("재고 해제 완료 → Saga 최종 실패 처리") {
            val order = baseOrder(
                status = OrderStatus.PAYMENT_COMPLETED,
                sagaState = OrderSagaState("order-001", currentStep = SagaStep.COMPENSATING,
                    failureReason = "배송 생성 실패")
            )
            val savedSlot = slot<Orders>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            orchestrator.onInventoryReply(inventoryReply(success = true), ack)

            then("status가 CANCELLED, sagaState가 FAILED로 변경되고 failureReason이 보존된다") {
                savedSlot.captured.status shouldBe OrderStatus.CANCELLED
                savedSlot.captured.sagaState?.currentStep shouldBe SagaStep.FAILED
                savedSlot.captured.sagaState?.failureReason shouldBe "배송 생성 실패"
            }

            then("추가 Outbox가 저장되지 않는다") {
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 취소 Saga
    // ════════════════════════════════════════════════════════════

    given("취소 Saga: Step 1 — 배송 취소") {
        `when`("배송 취소 성공 → 환불 명령 발행") {
            val order = baseOrder(
                status = OrderStatus.CANCELLING,
                sagaState = null,
                cancellationStatus = CancellationState(
                    initiatedAt = Instant.now(),
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            orchestrator.onDeliveryReply(deliveryReply(success = true), ack)

            then("cancellationStatus.deliveryCancelled=true, deliveryCancelledAt이 기록된다") {
                savedSlot.captured.cancellationStatus?.deliveryCancelled shouldBe true
                savedSlot.captured.cancellationStatus?.deliveryCancelledAt shouldNotBe null
                savedSlot.captured.status shouldBe OrderStatus.CANCELLING
            }

            then("payment-command (REFUND) Outbox가 올바른 sagaId로 저장된다") {
                outboxSlot.captured.topic shouldBe "payment-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
                outboxSlot.captured.payload.isNotEmpty() shouldBe true
            }
        }

        `when`("배송 취소 실패 → CANCELLATION_FAILED") {
            val order = baseOrder(
                status = OrderStatus.CANCELLING,
                sagaState = null,
                cancellationStatus = CancellationState(
                    initiatedAt = Instant.now(),
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            val savedSlot = slot<Orders>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            orchestrator.onDeliveryReply(deliveryReply(success = false), ack)

            then("status가 CANCELLATION_FAILED, compensationStatus=FAILED, deliveryCancellationError가 기록된다") {
                savedSlot.captured.status shouldBe OrderStatus.CANCELLATION_FAILED
                savedSlot.captured.cancellationStatus?.compensationStatus shouldBe CompensationStatus.FAILED
                savedSlot.captured.cancellationStatus?.deliveryCancellationError shouldNotBe null
            }

            then("환불 Outbox는 저장되지 않는다 (배송 진행 중일 수 있으므로)") {
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }

        `when`("중복 배송 취소 Reply (멱등성)") {
            val order = baseOrder(
                status = OrderStatus.CANCELLING,
                sagaState = null,
                cancellationStatus = CancellationState(
                    initiatedAt = Instant.now(),
                    deliveryCancelled = true,
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            coEvery { orderRepository.findById("order-001") } returns order

            orchestrator.onDeliveryReply(deliveryReply(success = true), ack)

            then("이미 처리됐으므로 save가 호출되지 않는다") {
                coVerify(exactly = 0) { orderRepository.save(any()) }
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }
    }

    given("취소 Saga: Step 2 — 결제 환불") {
        `when`("환불 성공 → 재고 복구 명령 발행") {
            val order = baseOrder(
                status = OrderStatus.CANCELLING,
                sagaState = null,
                cancellationStatus = CancellationState(
                    initiatedAt = Instant.now(),
                    deliveryCancelled = true,
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            orchestrator.onPaymentReply(paymentReply(success = true, paymentId = "pay-001"), ack)

            then("cancellationStatus.paymentRefunded=true, paymentRefundTxId가 기록된다") {
                savedSlot.captured.cancellationStatus?.paymentRefunded shouldBe true
                savedSlot.captured.cancellationStatus?.paymentRefundedAt shouldNotBe null
                savedSlot.captured.cancellationStatus?.paymentRefundTxId shouldBe "pay-001"
            }

            then("inventory-command (RELEASE) Outbox가 올바른 sagaId로 저장된다") {
                outboxSlot.captured.topic shouldBe "inventory-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
                outboxSlot.captured.payload.isNotEmpty() shouldBe true
            }
        }

        `when`("환불 실패 → CANCELLATION_FAILED") {
            val order = baseOrder(
                status = OrderStatus.CANCELLING,
                sagaState = null,
                cancellationStatus = CancellationState(
                    initiatedAt = Instant.now(),
                    deliveryCancelled = true,
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            val savedSlot = slot<Orders>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            orchestrator.onPaymentReply(paymentReply(success = false), ack)

            then("status가 CANCELLATION_FAILED, paymentRefundError가 기록된다") {
                savedSlot.captured.status shouldBe OrderStatus.CANCELLATION_FAILED
                savedSlot.captured.cancellationStatus?.compensationStatus shouldBe CompensationStatus.FAILED
                savedSlot.captured.cancellationStatus?.paymentRefundError shouldNotBe null
            }
        }
    }

    given("취소 Saga: Step 3 — 재고 복구 → CANCELLED") {
        `when`("재고 복구 성공 → CANCELLED 완료") {
            val order = baseOrder(
                status = OrderStatus.CANCELLING,
                sagaState = null,
                cancellationStatus = CancellationState(
                    initiatedAt = Instant.now(),
                    deliveryCancelled = true,
                    paymentRefunded = true,
                    paymentRefundTxId = "pay-001",
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            val savedSlot = slot<Orders>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            orchestrator.onInventoryReply(inventoryReply(success = true), ack)

            then("status=CANCELLED, compensationStatus=COMPLETED, inventoryRestored=true, cancelledAt이 기록된다") {
                savedSlot.captured.status shouldBe OrderStatus.CANCELLED
                savedSlot.captured.cancellationStatus?.compensationStatus shouldBe CompensationStatus.COMPLETED
                savedSlot.captured.cancellationStatus?.inventoryRestored shouldBe true
                savedSlot.captured.cancellationStatus?.inventoryRestoredAt shouldNotBe null
                savedSlot.captured.cancelledAt shouldNotBe null
            }

            then("추가 Outbox가 저장되지 않는다 (취소 완료)") {
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }

        `when`("재고 복구 실패 → CANCELLATION_FAILED") {
            val order = baseOrder(
                status = OrderStatus.CANCELLING,
                sagaState = null,
                cancellationStatus = CancellationState(
                    initiatedAt = Instant.now(),
                    deliveryCancelled = true,
                    paymentRefunded = true,
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            val savedSlot = slot<Orders>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            orchestrator.onInventoryReply(inventoryReply(success = false), ack)

            then("status=CANCELLATION_FAILED, inventoryRestorationError가 기록된다") {
                savedSlot.captured.status shouldBe OrderStatus.CANCELLATION_FAILED
                savedSlot.captured.cancellationStatus?.compensationStatus shouldBe CompensationStatus.FAILED
                savedSlot.captured.cancellationStatus?.inventoryRestorationError shouldNotBe null
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 반품 Saga
    // ════════════════════════════════════════════════════════════

    given("반품 Saga: Step 1 — 픽업 스케줄") {
        `when`("픽업 성공 → 환불 명령 발행") {
            val order = baseOrder(
                status = OrderStatus.RETURN_REQUESTED,
                sagaState = null,
                returnStatus = ReturnState(
                    initiatedAt = Instant.now(),
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            orchestrator.onDeliveryReply(deliveryReply(success = true), ack)

            then("status=RETURN_IN_PROGRESS, pickupScheduled=true, itemsReceived=true가 기록된다") {
                savedSlot.captured.status shouldBe OrderStatus.RETURN_IN_PROGRESS
                savedSlot.captured.returnStatus?.pickupScheduled shouldBe true
                savedSlot.captured.returnStatus?.pickupScheduledAt shouldNotBe null
                savedSlot.captured.returnStatus?.itemsReceived shouldBe true
                savedSlot.captured.returnStatus?.itemsReceivedAt shouldNotBe null
            }

            then("payment-command (REFUND) Outbox가 올바른 sagaId로 저장된다") {
                outboxSlot.captured.topic shouldBe "payment-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
                outboxSlot.captured.payload.isNotEmpty() shouldBe true
            }
        }

        `when`("픽업 실패 → RETURN_FAILED") {
            val order = baseOrder(
                status = OrderStatus.RETURN_REQUESTED,
                sagaState = null,
                returnStatus = ReturnState(
                    initiatedAt = Instant.now(),
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            val savedSlot = slot<Orders>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            orchestrator.onDeliveryReply(deliveryReply(success = false), ack)

            then("status=RETURN_FAILED, compensationStatus=FAILED, pickupError가 기록된다") {
                savedSlot.captured.status shouldBe OrderStatus.RETURN_FAILED
                savedSlot.captured.returnStatus?.compensationStatus shouldBe CompensationStatus.FAILED
                savedSlot.captured.returnStatus?.pickupError shouldNotBe null
            }

            then("Outbox가 저장되지 않는다") {
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }

        `when`("중복 픽업 Reply (멱등성)") {
            val order = baseOrder(
                status = OrderStatus.RETURN_REQUESTED,
                sagaState = null,
                returnStatus = ReturnState(
                    initiatedAt = Instant.now(),
                    pickupScheduled = true,
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            coEvery { orderRepository.findById("order-001") } returns order

            orchestrator.onDeliveryReply(deliveryReply(success = true), ack)

            then("이미 처리됐으므로 save가 호출되지 않는다") {
                coVerify(exactly = 0) { orderRepository.save(any()) }
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }
    }

    given("반품 Saga: Step 2 — 결제 환불") {
        `when`("환불 성공 → 재고 복구 명령 발행") {
            val order = baseOrder(
                status = OrderStatus.RETURN_IN_PROGRESS,
                sagaState = null,
                returnStatus = ReturnState(
                    initiatedAt = Instant.now(),
                    pickupScheduled = true,
                    itemsReceived = true,
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            orchestrator.onPaymentReply(paymentReply(success = true, paymentId = "pay-001"), ack)

            then("returnStatus.paymentRefunded=true, paymentRefundTxId가 기록된다") {
                savedSlot.captured.returnStatus?.paymentRefunded shouldBe true
                savedSlot.captured.returnStatus?.paymentRefundedAt shouldNotBe null
                savedSlot.captured.returnStatus?.paymentRefundTxId shouldBe "pay-001"
                savedSlot.captured.status shouldBe OrderStatus.RETURN_IN_PROGRESS
            }

            then("inventory-command (RELEASE) Outbox가 올바른 sagaId로 저장된다") {
                outboxSlot.captured.topic shouldBe "inventory-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
                outboxSlot.captured.payload.isNotEmpty() shouldBe true
            }
        }

        `when`("환불 실패 → RETURN_FAILED") {
            val order = baseOrder(
                status = OrderStatus.RETURN_IN_PROGRESS,
                sagaState = null,
                returnStatus = ReturnState(
                    initiatedAt = Instant.now(),
                    pickupScheduled = true,
                    itemsReceived = true,
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            val savedSlot = slot<Orders>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            orchestrator.onPaymentReply(paymentReply(success = false), ack)

            then("status=RETURN_FAILED, paymentRefundError가 기록된다") {
                savedSlot.captured.status shouldBe OrderStatus.RETURN_FAILED
                savedSlot.captured.returnStatus?.compensationStatus shouldBe CompensationStatus.FAILED
                savedSlot.captured.returnStatus?.paymentRefundError shouldNotBe null
            }
        }
    }

    given("반품 Saga: Step 3 — 재고 복구 → RETURN_COMPLETED") {
        `when`("재고 복구 성공 → RETURN_COMPLETED 완료") {
            val order = baseOrder(
                status = OrderStatus.RETURN_IN_PROGRESS,
                sagaState = null,
                returnStatus = ReturnState(
                    initiatedAt = Instant.now(),
                    pickupScheduled = true,
                    itemsReceived = true,
                    paymentRefunded = true,
                    paymentRefundTxId = "pay-001",
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            val savedSlot = slot<Orders>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            orchestrator.onInventoryReply(inventoryReply(success = true), ack)

            then("status=RETURN_COMPLETED, compensationStatus=COMPLETED, inventoryRestored=true, returnedAt이 기록된다") {
                savedSlot.captured.status shouldBe OrderStatus.RETURN_COMPLETED
                savedSlot.captured.returnStatus?.compensationStatus shouldBe CompensationStatus.COMPLETED
                savedSlot.captured.returnStatus?.inventoryRestored shouldBe true
                savedSlot.captured.returnStatus?.inventoryRestoredAt shouldNotBe null
                savedSlot.captured.returnedAt shouldNotBe null
            }

            then("추가 Outbox가 저장되지 않는다 (반품 완료)") {
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }

        `when`("재고 복구 실패 → RETURN_FAILED") {
            val order = baseOrder(
                status = OrderStatus.RETURN_IN_PROGRESS,
                sagaState = null,
                returnStatus = ReturnState(
                    initiatedAt = Instant.now(),
                    pickupScheduled = true,
                    itemsReceived = true,
                    paymentRefunded = true,
                    compensationStatus = CompensationStatus.IN_PROGRESS
                )
            )
            val savedSlot = slot<Orders>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            orchestrator.onInventoryReply(inventoryReply(success = false), ack)

            then("status=RETURN_FAILED, inventoryRestorationError가 기록된다") {
                savedSlot.captured.status shouldBe OrderStatus.RETURN_FAILED
                savedSlot.captured.returnStatus?.compensationStatus shouldBe CompensationStatus.FAILED
                savedSlot.captured.returnStatus?.inventoryRestorationError shouldNotBe null
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 늦은 Reply 보호 (Late Reply Protection)
    // ════════════════════════════════════════════════════════════

    given("늦은 Reply 보호") {
        `when`("CANCELLED 상태에서 InventoryReply 수신") {
            val order = baseOrder(
                status = OrderStatus.CANCELLED,
                sagaState = OrderSagaState("order-001", currentStep = SagaStep.FAILED)
            )
            coEvery { orderRepository.findById("order-001") } returns order

            orchestrator.onInventoryReply(inventoryReply(success = true), ack)

            then("save가 호출되지 않는다") {
                coVerify(exactly = 0) { orderRepository.save(any()) }
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }

            then("ack는 호출된다 (메시지 소비 확정)") {
                coVerify(atLeast = 1) { ack.acknowledge() }
            }
        }

        `when`("COMPLETED sagaState에서 DeliveryReply 수신") {
            val order = baseOrder(
                status = OrderStatus.PREPARING,
                sagaState = OrderSagaState("order-001", currentStep = SagaStep.COMPLETED)
            )
            coEvery { orderRepository.findById("order-001") } returns order

            orchestrator.onDeliveryReply(deliveryReply(success = true), ack)

            then("save가 호출되지 않는다") {
                coVerify(exactly = 0) { orderRepository.save(any()) }
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }

        `when`("RETURN_COMPLETED 상태에서 PaymentReply 수신") {
            val order = baseOrder(
                status = OrderStatus.RETURN_COMPLETED,
                sagaState = null,
                returnStatus = ReturnState(
                    initiatedAt = Instant.now(),
                    compensationStatus = CompensationStatus.COMPLETED
                )
            )
            coEvery { orderRepository.findById("order-001") } returns order

            orchestrator.onPaymentReply(paymentReply(success = true), ack)

            then("save가 호출되지 않는다") {
                coVerify(exactly = 0) { orderRepository.save(any()) }
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // shouldAck 플래그 — OptimisticLockingFailureException vs 일반 예외
    //
    // 설계 의도:
    //   OptimisticLockingFailureException → shouldAck = false → Kafka 재시도
    //   그 외 예외                        → shouldAck = true  → ack 후 DLT 라우팅
    // ════════════════════════════════════════════════════════════

    given("shouldAck 플래그 — InventoryReply") {
        val reply = inventoryReply(success = true)
        val order = baseOrder(sagaState = OrderSagaState("order-001", currentStep = SagaStep.INVENTORY_RESERVE))

        coEvery { orderRepository.findById("order-001") } returns order

        `when`("save 중 OptimisticLockingFailureException 발생") {
            coEvery { orderRepository.save(any()) } throws OptimisticLockingFailureException("version conflict")

            then("예외가 전파되고 ack 는 호출되지 않는다 (Kafka 재시도 유도)") {
                shouldThrow<OptimisticLockingFailureException> {
                    orchestrator.onInventoryReply(reply, ack)
                }
                coVerify(exactly = 0) { ack.acknowledge() }
            }
        }

        `when`("save 중 일반 RuntimeException 발생") {
            coEvery { orderRepository.save(any()) } throws RuntimeException("unexpected")

            then("예외가 전파되고 ack 는 호출된다 (DLT 로 라우팅)") {
                shouldThrow<RuntimeException> {
                    orchestrator.onInventoryReply(reply, ack)
                }
                coVerify(exactly = 1) { ack.acknowledge() }
            }
        }
    }

    given("shouldAck 플래그 — PaymentReply") {
        val reply = paymentReply(success = true)
        val order = baseOrder(
            sagaState = OrderSagaState("order-001", currentStep = SagaStep.PAYMENT_PROCESS, inventoryReserved = true)
        )

        coEvery { orderRepository.findById("order-001") } returns order

        `when`("save 중 OptimisticLockingFailureException 발생") {
            coEvery { orderRepository.save(any()) } throws OptimisticLockingFailureException("version conflict")

            then("ack 는 호출되지 않는다") {
                shouldThrow<OptimisticLockingFailureException> {
                    orchestrator.onPaymentReply(reply, ack)
                }
                coVerify(exactly = 0) { ack.acknowledge() }
            }
        }

        `when`("save 중 일반 RuntimeException 발생") {
            coEvery { orderRepository.save(any()) } throws RuntimeException("unexpected")

            then("ack 는 호출된다") {
                shouldThrow<RuntimeException> {
                    orchestrator.onPaymentReply(reply, ack)
                }
                coVerify(exactly = 1) { ack.acknowledge() }
            }
        }
    }

    given("shouldAck 플래그 — DeliveryReply") {
        val reply = deliveryReply(success = true)
        val order = baseOrder(
            sagaState = OrderSagaState(
                "order-001", currentStep = SagaStep.DELIVERY_CREATE,
                inventoryReserved = true, paymentProcessed = true
            )
        )

        coEvery { orderRepository.findById("order-001") } returns order

        `when`("save 중 OptimisticLockingFailureException 발생") {
            coEvery { orderRepository.save(any()) } throws OptimisticLockingFailureException("version conflict")

            then("ack 는 호출되지 않는다") {
                shouldThrow<OptimisticLockingFailureException> {
                    orchestrator.onDeliveryReply(reply, ack)
                }
                coVerify(exactly = 0) { ack.acknowledge() }
            }
        }

        `when`("save 중 일반 RuntimeException 발생") {
            coEvery { orderRepository.save(any()) } throws RuntimeException("unexpected")

            then("ack 는 호출된다") {
                shouldThrow<RuntimeException> {
                    orchestrator.onDeliveryReply(reply, ack)
                }
                coVerify(exactly = 1) { ack.acknowledge() }
            }
        }
    }
})
