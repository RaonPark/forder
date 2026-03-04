package org.example.orderservice

import common.document.order.CancellationState
import common.document.order.CompensationStatus
import common.document.order.OrderStatus
import common.document.order.ReturnState
import common.saga.OrderSagaState
import common.saga.SagaStep
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.example.orderservice.document.OrderItem
import org.example.orderservice.document.Orders
import org.example.orderservice.document.OutboxEvent
import org.example.orderservice.dto.TimeoutOutcome
import org.example.orderservice.repository.OrderRepository
import org.example.orderservice.repository.OutboxRepository
import org.example.orderservice.saga.SagaTimeoutHandler
import java.math.BigDecimal
import java.time.Instant

class SagaTimeoutHandlerTest : BehaviorSpec({

    isolationMode = IsolationMode.InstancePerTest

    afterEach { clearAllMocks() }

    val orderRepository  = mockk<OrderRepository>()
    val outboxRepository = mockk<OutboxRepository>()
    val handler = SagaTimeoutHandler(orderRepository, outboxRepository)

    // ─── 픽스처 ──────────────────────────────────────────────────

    fun baseOrder(
        orderId: String = "order-001",
        status: OrderStatus = OrderStatus.PENDING,
        sagaState: OrderSagaState? = OrderSagaState(sagaId = "order-001"),
        cancellationStatus: CancellationState? = null,
        returnStatus: ReturnState? = null
    ) = Orders(
        orderId = orderId,
        userId  = "user-001",
        status  = status,
        items   = listOf(OrderItem("prod-001", 2, BigDecimal("10000"))),
        totalAmount      = BigDecimal("20000"),
        deliveryAddress  = "서울시 강남구",
        receiverName     = "홍길동",
        receiverPhone    = "010-1234-5678",
        sagaState        = sagaState,
        cancellationStatus = cancellationStatus,
        returnStatus     = returnStatus,
        createdAt        = Instant.now().minusSeconds(3600)
    )

    fun setupSave() {
        val orderSlot  = slot<Orders>()
        val outboxSlot = slot<OutboxEvent>()
        coEvery { orderRepository.save(capture(orderSlot))   } answers { orderSlot.captured }
        coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }
    }

    // ════════════════════════════════════════════════════════════
    // 생성 Saga (PENDING) 타임아웃
    // ════════════════════════════════════════════════════════════

    given("생성 Saga 타임아웃 — PENDING") {

        `when`("아무 단계도 완료되지 않음") {
            val order = baseOrder(
                sagaState = OrderSagaState("order-001", currentStep = SagaStep.INVENTORY_RESERVE)
            )
            val savedOrder = slot<Orders>()
            coEvery { orderRepository.save(capture(savedOrder)) } answers { savedOrder.captured }

            val outcome = handler.handle(order)

            then("CANCELLED 로 저장된다") {
                outcome shouldBe TimeoutOutcome.CANCELLED
                savedOrder.captured.status shouldBe OrderStatus.CANCELLED
                savedOrder.captured.sagaState?.currentStep shouldBe SagaStep.FAILED
                savedOrder.captured.sagaState?.failureReason shouldBe "Saga timeout"
            }

            then("outbox 이벤트는 발행하지 않는다 (보상 불필요)") {
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }

        `when`("재고 예약만 완료 (inventoryReserved = true)") {
            val order = baseOrder(
                sagaState = OrderSagaState(
                    "order-001",
                    currentStep = SagaStep.PAYMENT_PROCESS,
                    inventoryReserved = true
                )
            )
            val outboxSlot = slot<OutboxEvent>()
            coEvery { orderRepository.save(any()) } answers { firstArg() }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            val outcome = handler.handle(order)

            then("CANCELLED 로 저장된다") {
                outcome shouldBe TimeoutOutcome.CANCELLED
            }

            then("inventory-command (RELEASE) 만 발행된다") {
                coVerify(exactly = 1) { outboxRepository.save(any()) }
                outboxSlot.captured.topic shouldBe "inventory-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
            }
        }

        `when`("결제까지 완료 (paymentProcessed = true)") {
            val order = baseOrder(
                sagaState = OrderSagaState(
                    "order-001",
                    currentStep = SagaStep.DELIVERY_CREATE,
                    inventoryReserved = true,
                    paymentProcessed = true
                )
            )
            val outboxSlots = mutableListOf<OutboxEvent>()
            coEvery { orderRepository.save(any()) } answers { firstArg() }
            coEvery { outboxRepository.save(any()) } answers {
                firstArg<OutboxEvent>().also { outboxSlots += it }
            }

            val outcome = handler.handle(order)

            then("CANCELLED 로 저장된다") {
                outcome shouldBe TimeoutOutcome.CANCELLED
            }

            then("payment-command (REFUND) 와 inventory-command (RELEASE) 가 모두 발행된다") {
                outboxSlots.size shouldBe 2
                outboxSlots.any { it.topic == "payment-command" } shouldBe true
                outboxSlots.any { it.topic == "inventory-command" } shouldBe true
            }
        }

        `when`("COMPENSATING 단계에서 멈춤 (보상 자체가 교착)") {
            val order = baseOrder(
                sagaState = OrderSagaState(
                    "order-001",
                    currentStep = SagaStep.COMPENSATING,
                    inventoryReserved = true,
                    paymentProcessed = true
                )
            )

            val outcome = handler.handle(order)

            then("SKIPPED 를 반환한다 — 수동 개입 필요") {
                outcome shouldBe TimeoutOutcome.SKIPPED
            }

            then("orderRepository.save 는 호출되지 않는다") {
                coVerify(exactly = 0) { orderRepository.save(any()) }
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 취소 Saga (CANCELLING) 타임아웃
    // ════════════════════════════════════════════════════════════

    given("취소 Saga 타임아웃 — CANCELLING") {

        `when`("아무 단계도 완료되지 않음") {
            val order = baseOrder(
                status = OrderStatus.CANCELLING,
                cancellationStatus = CancellationState(
                    initiatedAt = Instant.now().minusSeconds(7200)
                ),
                sagaState = null
            )
            val savedOrder = slot<Orders>()
            coEvery { orderRepository.save(capture(savedOrder)) } answers { savedOrder.captured }

            val outcome = handler.handle(order)

            then("CANCELLATION_FAILED 로 저장된다") {
                outcome shouldBe TimeoutOutcome.CANCELLATION_FAILED
                savedOrder.captured.status shouldBe OrderStatus.CANCELLATION_FAILED
                savedOrder.captured.cancellationStatus?.compensationStatus shouldBe CompensationStatus.FAILED
                savedOrder.captured.cancellationStatus?.completedAt shouldNotBe null
            }

            then("outbox 이벤트는 발행되지 않는다") {
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }

        `when`("배송 취소됐지만 환불 누락 (deliveryCancelled = true, paymentRefunded = false)") {
            val order = baseOrder(
                status = OrderStatus.CANCELLING,
                cancellationStatus = CancellationState(
                    initiatedAt = Instant.now().minusSeconds(7200),
                    deliveryCancelled = true
                ),
                sagaState = null
            )
            val outboxSlot = slot<OutboxEvent>()
            coEvery { orderRepository.save(any()) } answers { firstArg() }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            val outcome = handler.handle(order)

            then("CANCELLATION_FAILED 로 저장되고 환불 명령이 발행된다") {
                outcome shouldBe TimeoutOutcome.CANCELLATION_FAILED
                outboxSlot.captured.topic shouldBe "payment-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
            }
        }

        `when`("환불 완료됐지만 재고 복구 누락 (paymentRefunded = true, inventoryRestored = false)") {
            val order = baseOrder(
                status = OrderStatus.CANCELLING,
                cancellationStatus = CancellationState(
                    initiatedAt = Instant.now().minusSeconds(7200),
                    deliveryCancelled = true,
                    paymentRefunded   = true
                ),
                sagaState = null
            )
            val outboxSlot = slot<OutboxEvent>()
            coEvery { orderRepository.save(any()) } answers { firstArg() }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            val outcome = handler.handle(order)

            then("CANCELLATION_FAILED 로 저장되고 재고 해제 명령이 발행된다") {
                outcome shouldBe TimeoutOutcome.CANCELLATION_FAILED
                outboxSlot.captured.topic shouldBe "inventory-command"
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 반품 Saga (RETURN_REQUESTED / RETURN_IN_PROGRESS) 타임아웃
    // ════════════════════════════════════════════════════════════

    given("반품 Saga 타임아웃 — RETURN_REQUESTED") {

        `when`("픽업 스케줄 전 타임아웃") {
            val order = baseOrder(
                status = OrderStatus.RETURN_REQUESTED,
                returnStatus = ReturnState(initiatedAt = Instant.now().minusSeconds(9000)),
                sagaState = null
            )
            val savedOrder = slot<Orders>()
            coEvery { orderRepository.save(capture(savedOrder)) } answers { savedOrder.captured }

            val outcome = handler.handle(order)

            then("RETURN_FAILED 로 저장된다") {
                outcome shouldBe TimeoutOutcome.RETURN_FAILED
                savedOrder.captured.status shouldBe OrderStatus.RETURN_FAILED
                savedOrder.captured.returnStatus?.compensationStatus shouldBe CompensationStatus.FAILED
            }

            then("outbox 이벤트는 발행되지 않는다") {
                coVerify(exactly = 0) { outboxRepository.save(any()) }
            }
        }
    }

    given("반품 Saga 타임아웃 — RETURN_IN_PROGRESS") {

        `when`("상품 수령 후 환불 누락 (itemsReceived = true, paymentRefunded = false)") {
            val order = baseOrder(
                status = OrderStatus.RETURN_IN_PROGRESS,
                returnStatus = ReturnState(
                    initiatedAt    = Instant.now().minusSeconds(9000),
                    pickupScheduled = true,
                    itemsReceived  = true
                ),
                sagaState = null
            )
            val outboxSlot = slot<OutboxEvent>()
            coEvery { orderRepository.save(any()) } answers { firstArg() }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            val outcome = handler.handle(order)

            then("RETURN_FAILED 로 저장되고 환불 명령이 발행된다") {
                outcome shouldBe TimeoutOutcome.RETURN_FAILED
                outboxSlot.captured.topic shouldBe "payment-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
            }
        }

        `when`("환불 완료됐지만 재고 복구 누락 (paymentRefunded = true, inventoryRestored = false)") {
            val order = baseOrder(
                status = OrderStatus.RETURN_IN_PROGRESS,
                returnStatus = ReturnState(
                    initiatedAt    = Instant.now().minusSeconds(9000),
                    pickupScheduled = true,
                    itemsReceived  = true,
                    paymentRefunded = true
                ),
                sagaState = null
            )
            val outboxSlot = slot<OutboxEvent>()
            coEvery { orderRepository.save(any()) } answers { firstArg() }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            val outcome = handler.handle(order)

            then("RETURN_FAILED 로 저장되고 재고 해제 명령이 발행된다") {
                outcome shouldBe TimeoutOutcome.RETURN_FAILED
                outboxSlot.captured.topic shouldBe "inventory-command"
            }
        }
    }
})
