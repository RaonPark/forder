package org.example.orderservice

import common.document.order.CancellationState
import common.document.order.OrderStatus
import common.saga.OrderSagaState
import common.saga.SagaStep
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.example.orderservice.document.OrderItem
import org.example.orderservice.document.Orders
import org.example.orderservice.dto.TimeoutOutcome
import org.example.orderservice.repository.OrderRepository
import org.example.orderservice.saga.SagaTimeoutHandler
import org.example.orderservice.service.SagaTimeoutService
import java.math.BigDecimal
import java.time.Instant

class SagaTimeoutServiceTest : BehaviorSpec({

    isolationMode = IsolationMode.InstancePerTest

    afterEach { clearAllMocks() }

    val orderRepository = mockk<OrderRepository>()
    val timeoutHandler  = mockk<SagaTimeoutHandler>()
    val service = SagaTimeoutService(orderRepository, timeoutHandler)

    // ─── 픽스처 ──────────────────────────────────────────────────

    // 임계값(30분)을 넘겨 updatedAt 을 설정
    fun stuckOrder(
        orderId: String = "order-001",
        status: OrderStatus = OrderStatus.PENDING,
        minutesAgo: Long = 60,          // 기본 60분 전 → 30분 임계값 초과
        sagaState: OrderSagaState? = OrderSagaState("order-001"),
        cancellationStatus: CancellationState? = null
    ) = Orders(
        orderId    = orderId,
        userId     = "user-001",
        status     = status,
        items      = listOf(OrderItem("prod-001", 2, BigDecimal("10000"))),
        totalAmount     = BigDecimal("20000"),
        deliveryAddress = "서울시 강남구",
        receiverName    = "홍길동",
        receiverPhone   = "010-1234-5678",
        sagaState       = sagaState,
        cancellationStatus = cancellationStatus,
        updatedAt  = Instant.now().minusSeconds(minutesAgo * 60),
        createdAt  = Instant.now().minusSeconds(minutesAgo * 60 + 60)
    )

    // ════════════════════════════════════════════════════════════
    // processStuckOrders
    // ════════════════════════════════════════════════════════════

    given("processStuckOrders") {

        `when`("타임아웃 임계값을 초과한 PENDING 주문이 있을 때") {
            val order = stuckOrder(status = OrderStatus.PENDING, minutesAgo = 60)

            // findByStatusInAndUpdatedAtBefore 는 30분 전 기준으로 쿼리
            coEvery {
                orderRepository.findByStatusInAndUpdatedAtBefore(any(), any())
            } returns flowOf(order)

            coEvery { timeoutHandler.handle(order) } returns TimeoutOutcome.CANCELLED

            val result = service.processStuckOrders()

            then("handler 가 호출되고 결과에 반영된다") {
                coVerify(exactly = 1) { timeoutHandler.handle(order) }
                result.processedCount shouldBe 1
                result.cancelledOrders shouldBe listOf("order-001")
                result.cancellationFailedOrders shouldBe emptyList()
                result.returnFailedOrders shouldBe emptyList()
                result.skippedOrders shouldBe emptyList()
            }
        }

        `when`("CANCELLING 주문이 60분 미만 (임계값 미달)") {
            // 쿼리 자체는 30분 기준이므로 반환됨
            // 하지만 서비스 내부에서 CANCELLING 임계값(60분)으로 재필터링 → 스킵
            val order = stuckOrder(
                status = OrderStatus.CANCELLING,
                minutesAgo = 45   // 45분 전 → CANCELLING 임계값 60분 미달
            )

            coEvery {
                orderRepository.findByStatusInAndUpdatedAtBefore(any(), any())
            } returns flowOf(order)

            val result = service.processStuckOrders()

            then("handler 가 호출되지 않는다") {
                coVerify(exactly = 0) { timeoutHandler.handle(any()) }
                result.processedCount shouldBe 0
            }
        }

        `when`("여러 상태의 주문이 혼재할 때") {
            val pendingOrder      = stuckOrder("order-001", OrderStatus.PENDING,   minutesAgo = 60)
            val cancellingOrder   = stuckOrder("order-002", OrderStatus.CANCELLING, minutesAgo = 90)
            val returnOrder       = stuckOrder("order-003", OrderStatus.RETURN_IN_PROGRESS, minutesAgo = 150)

            coEvery {
                orderRepository.findByStatusInAndUpdatedAtBefore(any(), any())
            } returns flowOf(pendingOrder, cancellingOrder, returnOrder)

            coEvery { timeoutHandler.handle(pendingOrder)    } returns TimeoutOutcome.CANCELLED
            coEvery { timeoutHandler.handle(cancellingOrder) } returns TimeoutOutcome.CANCELLATION_FAILED
            coEvery { timeoutHandler.handle(returnOrder)     } returns TimeoutOutcome.RETURN_FAILED

            val result = service.processStuckOrders()

            then("각 주문이 올바른 결과로 집계된다") {
                result.processedCount shouldBe 3
                result.cancelledOrders shouldBe listOf("order-001")
                result.cancellationFailedOrders shouldBe listOf("order-002")
                result.returnFailedOrders shouldBe listOf("order-003")
                result.skippedOrders shouldBe emptyList()
            }
        }

        `when`("handler 에서 예외가 발생해도 나머지 주문은 계속 처리된다") {
            val failingOrder  = stuckOrder("order-001", OrderStatus.PENDING, minutesAgo = 60)
            val successOrder  = stuckOrder("order-002", OrderStatus.PENDING, minutesAgo = 60)

            coEvery {
                orderRepository.findByStatusInAndUpdatedAtBefore(any(), any())
            } returns flowOf(failingOrder, successOrder)

            coEvery { timeoutHandler.handle(failingOrder) } throws RuntimeException("DB error")
            coEvery { timeoutHandler.handle(successOrder) } returns TimeoutOutcome.CANCELLED

            val result = service.processStuckOrders()

            then("실패한 주문은 skipped, 성공한 주문은 결과에 반영된다") {
                result.cancelledOrders shouldBe listOf("order-002")
                result.skippedOrders shouldBe listOf("order-001")
                result.processedCount shouldBe 1
            }
        }

        `when`("SKIPPED 결과 (COMPENSATING 단계 교착)") {
            val order = stuckOrder(
                status = OrderStatus.PENDING,
                minutesAgo = 60,
                sagaState = OrderSagaState("order-001", currentStep = SagaStep.COMPENSATING)
            )

            coEvery {
                orderRepository.findByStatusInAndUpdatedAtBefore(any(), any())
            } returns flowOf(order)

            coEvery { timeoutHandler.handle(order) } returns TimeoutOutcome.SKIPPED

            val result = service.processStuckOrders()

            then("processedCount 에 포함되지 않고 skippedOrders 에 기록된다") {
                result.processedCount shouldBe 0
                result.skippedOrders shouldBe listOf("order-001")
            }
        }

        `when`("타임아웃 대상 주문이 없을 때") {
            coEvery {
                orderRepository.findByStatusInAndUpdatedAtBefore(any(), any())
            } returns flowOf()

            val result = service.processStuckOrders()

            then("processedCount 가 0이다") {
                result.processedCount shouldBe 0
                result.cancelledOrders shouldBe emptyList()
            }
        }
    }
})
