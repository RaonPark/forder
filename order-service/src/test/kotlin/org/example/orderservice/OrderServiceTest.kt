package org.example.orderservice

import common.document.order.CancellationState
import common.document.order.CompensationStatus
import common.document.order.OrderStatus
import common.document.order.ReturnState
import common.saga.OrderSagaState
import common.saga.SagaStep
import io.kotest.assertions.throwables.shouldThrow
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
import org.example.orderservice.dto.CancelOrderRequest
import org.example.orderservice.dto.CreateOrderRequest
import org.example.orderservice.dto.OrderItemRequest
import org.example.orderservice.dto.ReturnOrderRequest
import org.example.orderservice.repository.OrderRepository
import org.example.orderservice.repository.OutboxRepository
import org.example.orderservice.service.OrderService
import java.math.BigDecimal
import java.time.Instant

class OrderServiceTest : BehaviorSpec({

    isolationMode = IsolationMode.InstancePerTest

    afterEach { clearAllMocks() }

    val orderRepository = mockk<OrderRepository>()
    val outboxRepository = mockk<OutboxRepository>()
    val service = OrderService(orderRepository, outboxRepository)

    // ─── 픽스처 ──────────────────────────────────────────────────

    fun sampleOrder(
        orderId: String = "order-001",
        status: OrderStatus = OrderStatus.PAYMENT_COMPLETED,
        sagaState: OrderSagaState? = OrderSagaState(sagaId = "order-001", currentStep = SagaStep.COMPLETED),
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

    fun sampleCreateRequest() = CreateOrderRequest(
        userId = "user-001",
        items = listOf(OrderItemRequest("prod-001", 2, BigDecimal("10000"))),
        deliveryAddress = "서울시 강남구",
        receiverName = "홍길동",
        receiverPhone = "010-1234-5678"
    )

    // ════════════════════════════════════════════════════════════
    // createOrder
    // ════════════════════════════════════════════════════════════

    given("createOrder") {
        `when`("유효한 요청이 오면") {
            val orderSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.save(capture(orderSlot)) } answers { orderSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            val response = service.createOrder(sampleCreateRequest())

            then("orderId가 생성되고 totalAmount가 정확하다") {
                response.orderId shouldNotBe null
                response.totalAmount shouldBe BigDecimal("20000")
            }

            then("Order가 PENDING + INVENTORY_RESERVE sagaState로 저장된다") {
                orderSlot.captured.status shouldBe OrderStatus.PENDING
                orderSlot.captured.sagaState?.currentStep shouldBe SagaStep.INVENTORY_RESERVE
                orderSlot.captured.sagaState?.sagaId shouldBe orderSlot.captured.orderId
                orderSlot.captured.userId shouldBe "user-001"
                orderSlot.captured.totalAmount shouldBe BigDecimal("20000")
            }

            then("Outbox에 inventory-command 이벤트가 저장된다") {
                outboxSlot.captured.topic shouldBe "inventory-command"
                outboxSlot.captured.aggregateId shouldBe response.orderId
                outboxSlot.captured.payload.isNotEmpty() shouldBe true
            }

            then("orderRepository.save는 정확히 1번만 호출된다 (더블 세이브 없음)") {
                coVerify(exactly = 1) { orderRepository.save(any()) }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // getOrder
    // ════════════════════════════════════════════════════════════

    given("getOrder") {
        `when`("존재하는 orderId") {
            coEvery { orderRepository.findById("order-001") } returns sampleOrder()

            val result = service.getOrder("order-001")

            then("OrderResponse가 반환된다") {
                result.orderId shouldBe "order-001"
                result.status shouldBe OrderStatus.PAYMENT_COMPLETED
                result.items.size shouldBe 1
                result.items[0].productId shouldBe "prod-001"
                result.totalAmount shouldBe BigDecimal("20000")
            }
        }

        `when`("존재하지 않는 orderId") {
            coEvery { orderRepository.findById("nonexistent") } returns null

            then("NoSuchElementException이 발생한다") {
                shouldThrow<NoSuchElementException> {
                    service.getOrder("nonexistent")
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // cancelOrder
    // ════════════════════════════════════════════════════════════

    given("cancelOrder") {
        val cancelRequest = CancelOrderRequest(reason = "고객 변심")

        `when`("PAYMENT_COMPLETED 상태 주문 취소") {
            val order = sampleOrder(status = OrderStatus.PAYMENT_COMPLETED)
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            val result = service.cancelOrder("order-001", cancelRequest)

            then("status가 CANCELLING으로 변경된다") {
                result.status shouldBe OrderStatus.CANCELLING
                savedSlot.captured.status shouldBe OrderStatus.CANCELLING
            }

            then("CancellationState가 IN_PROGRESS로 초기화된다") {
                savedSlot.captured.cancellationStatus shouldNotBe null
                savedSlot.captured.cancellationStatus?.compensationStatus shouldBe CompensationStatus.IN_PROGRESS
                savedSlot.captured.cancellationStatus?.initiatedAt shouldNotBe null
                savedSlot.captured.cancelledReason shouldBe "고객 변심"
            }

            then("Outbox에 delivery-command (CANCEL)이 sagaId 포함하여 저장된다") {
                outboxSlot.captured.topic shouldBe "delivery-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
                outboxSlot.captured.payload.isNotEmpty() shouldBe true
            }
        }

        `when`("PREPARING 상태 주문 취소") {
            val order = sampleOrder(status = OrderStatus.PREPARING)
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            val result = service.cancelOrder("order-001", cancelRequest)

            then("CANCELLING으로 변경되고 CancellationState가 초기화된다") {
                result.status shouldBe OrderStatus.CANCELLING
                savedSlot.captured.status shouldBe OrderStatus.CANCELLING
                savedSlot.captured.cancellationStatus?.compensationStatus shouldBe CompensationStatus.IN_PROGRESS
            }

            then("Outbox에 delivery-command (CANCEL)이 저장된다") {
                outboxSlot.captured.topic shouldBe "delivery-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
            }
        }

        `when`("PENDING 상태 취소 시도 (Saga 진행 중)") {
            coEvery { orderRepository.findById("order-001") } returns sampleOrder(status = OrderStatus.PENDING)

            then("IllegalStateException이 발생한다 (상태 불변식 위반)") {
                shouldThrow<IllegalStateException> {
                    service.cancelOrder("order-001", cancelRequest)
                }
            }
        }

        `when`("SHIPPING 상태 취소 시도") {
            coEvery { orderRepository.findById("order-001") } returns sampleOrder(status = OrderStatus.SHIPPING)

            then("IllegalStateException이 발생한다") {
                shouldThrow<IllegalStateException> {
                    service.cancelOrder("order-001", cancelRequest)
                }
            }
        }

        `when`("DELIVERED 상태 취소 시도") {
            coEvery { orderRepository.findById("order-001") } returns sampleOrder(status = OrderStatus.DELIVERED)

            then("IllegalStateException이 발생한다") {
                shouldThrow<IllegalStateException> {
                    service.cancelOrder("order-001", cancelRequest)
                }
            }
        }

        `when`("이미 CANCELLING 상태에서 재취소 시도") {
            coEvery { orderRepository.findById("order-001") } returns sampleOrder(status = OrderStatus.CANCELLING)

            then("IllegalStateException이 발생한다") {
                shouldThrow<IllegalStateException> {
                    service.cancelOrder("order-001", cancelRequest)
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // requestReturn
    // ════════════════════════════════════════════════════════════

    given("requestReturn") {
        val returnRequest = ReturnOrderRequest(reason = "상품 불량")

        `when`("DELIVERED 상태 반품 신청") {
            val order = sampleOrder(status = OrderStatus.DELIVERED)
            val savedSlot = slot<Orders>()
            val outboxSlot = slot<OutboxEvent>()

            coEvery { orderRepository.findById("order-001") } returns order
            coEvery { orderRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            coEvery { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured }

            val result = service.requestReturn("order-001", returnRequest)

            then("status가 RETURN_REQUESTED로 변경된다") {
                result.status shouldBe OrderStatus.RETURN_REQUESTED
                savedSlot.captured.status shouldBe OrderStatus.RETURN_REQUESTED
            }

            then("ReturnState가 IN_PROGRESS로 초기화된다") {
                savedSlot.captured.returnStatus shouldNotBe null
                savedSlot.captured.returnStatus?.compensationStatus shouldBe CompensationStatus.IN_PROGRESS
                savedSlot.captured.returnStatus?.initiatedAt shouldNotBe null
                savedSlot.captured.returnReason shouldBe "상품 불량"
            }

            then("Outbox에 delivery-command (RETURN_PICKUP)이 sagaId 포함하여 저장된다") {
                outboxSlot.captured.topic shouldBe "delivery-command"
                outboxSlot.captured.aggregateId shouldBe "order-001"
                outboxSlot.captured.payload.isNotEmpty() shouldBe true
            }
        }

        `when`("PREPARING 상태 반품 신청") {
            coEvery { orderRepository.findById("order-001") } returns sampleOrder(status = OrderStatus.PREPARING)

            then("IllegalStateException이 발생한다") {
                shouldThrow<IllegalStateException> {
                    service.requestReturn("order-001", returnRequest)
                }
            }
        }

        `when`("RETURN_REQUESTED 상태에서 반품 중복 신청") {
            coEvery { orderRepository.findById("order-001") } returns sampleOrder(status = OrderStatus.RETURN_REQUESTED)

            then("IllegalStateException이 발생한다") {
                shouldThrow<IllegalStateException> {
                    service.requestReturn("order-001", returnRequest)
                }
            }
        }
    }
})
