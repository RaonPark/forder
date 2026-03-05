package org.example.orderservice

import com.ninjasquad.springmockk.MockkBean
import common.document.order.CancellationState
import common.document.order.OrderStatus
import common.saga.OrderSagaState
import common.saga.SagaStep
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.example.orderservice.document.OrderItem
import org.example.orderservice.document.Orders
import org.example.orderservice.dto.TimeoutOutcome
import org.example.orderservice.dto.TimeoutProcessResult
import org.example.orderservice.repository.OrderRepository
import org.example.orderservice.repository.OutboxRepository
import org.example.orderservice.saga.SagaTimeoutHandler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * InternalSagaController 통합 테스트.
 *
 * 테스트 범위:
 *   Controller → SagaTimeoutService → OrderRepository(MongoDB) → 응답 JSON
 *
 * SagaTimeoutHandler 는 @MockkBean 으로 대체.
 * - 이유 1: 단위 테스트(SagaTimeoutHandlerTest)에서 이미 충분히 검증됨
 * - 이유 2: @Transactional 이 요구하는 MongoDB Replica Set 없이 테스트 가능
 *          (TestcontainersConfiguration 의 MongoDBContainer 는 Standalone 모드)
 *
 * 이 테스트가 검증하는 것:
 *   1. POST /internal/saga/timeout/process 엔드포인트 응답 형식
 *   2. SagaTimeoutService 의 상태별 임계값 필터링 로직 (in-memory re-filter)
 *   3. handler.handle() 호출 여부 (올바른 주문만 위임)
 *   4. TimeoutProcessResult 집계 정확성
 *   5. 멱등성: 이미 terminal 상태 주문은 쿼리에서 제외
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration::class)
class InternalSagaControllerIT {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var orderRepository: OrderRepository

    @Autowired
    lateinit var outboxRepository: OutboxRepository

    @Autowired
    lateinit var mongoTemplate: ReactiveMongoTemplate

    // @Transactional 이 있는 Handler 만 교체 — Service/Repository 는 실제 구현 사용
    @MockkBean
    lateinit var sagaTimeoutHandler: SagaTimeoutHandler

    @BeforeEach
    fun setUp() = runBlocking {
        orderRepository.deleteAll()
        outboxRepository.deleteAll()
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    /**
     * 테스트용 주문을 MongoDB 에 삽입한다.
     *
     * @LastModifiedDate 로 인해 save() 시 updatedAt 이 현재 시각으로 덮어쓰인다.
     * save() 후 ReactiveMongoTemplate.updateFirst() 로 updatedAt 을 직접 패치한다.
     * (Template 의 updateFirst() 는 Spring Data auditing 콜백을 트리거하지 않음)
     */
    private suspend fun insertStuckOrder(
        orderId: String = UUID.randomUUID().toString(),
        status: OrderStatus = OrderStatus.PENDING,
        minutesAgo: Long = 60,
        sagaState: OrderSagaState? = OrderSagaState(sagaId = orderId),
        cancellationStatus: CancellationState? = null
    ): Orders {
        val order = Orders(
            orderId            = orderId,
            userId             = "user-test",
            status             = status,
            items              = listOf(OrderItem("prod-001", 1, BigDecimal("10000"))),
            totalAmount        = BigDecimal("10000"),
            deliveryAddress    = "서울시 강남구",
            receiverName       = "테스트",
            receiverPhone      = "010-0000-0000",
            sagaState          = sagaState,
            cancellationStatus = cancellationStatus
        )
        orderRepository.save(order)

        // updatedAt 을 과거로 강제 설정 (auditing 콜백 우회)
        mongoTemplate.updateFirst(
            Query(Criteria.where("_id").`is`(orderId)),
            Update().set("updatedAt", Instant.now().minusSeconds(minutesAgo * 60)),
            Orders::class.java
        ).awaitSingle()

        return orderRepository.findById(orderId)!!
    }

    // ════════════════════════════════════════════════════════════════════════
    // 기본 동작
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `빈 DB - processedCount 0 반환`() {
        webTestClient.post()
            .uri("/internal/saga/timeout/process")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.processedCount").isEqualTo(0)
            .jsonPath("$.cancelledOrders").isEmpty
            .jsonPath("$.skippedOrders").isEmpty
    }

    @Test
    fun `30분 이상 멈춘 PENDING 주문 - handler 위임 및 CANCELLED 응답`() = runBlocking {
        val order = insertStuckOrder(
            orderId    = "order-pending-001",
            status     = OrderStatus.PENDING,
            minutesAgo = 60
        )
        coEvery { sagaTimeoutHandler.handle(order) } returns TimeoutOutcome.CANCELLED

        val result = webTestClient.post()
            .uri("/internal/saga/timeout/process")
            .exchange()
            .expectStatus().isOk
            .expectBody<TimeoutProcessResult>()
            .returnResult().responseBody!!

        result.processedCount shouldBe 1
        result.cancelledOrders shouldContainExactly listOf("order-pending-001")
        result.skippedOrders.shouldBeEmpty()

        coVerify(exactly = 1) { sagaTimeoutHandler.handle(order) }
    }

    @Test
    fun `60분 이상 멈춘 CANCELLING 주문 - handler 위임 및 CANCELLATION_FAILED 응답`() = runBlocking<Unit> {
        val order = insertStuckOrder(
            orderId            = "order-cancelling-001",
            status             = OrderStatus.CANCELLING,
            minutesAgo         = 90,
            sagaState          = null,
            cancellationStatus = CancellationState(initiatedAt = Instant.now().minusSeconds(90 * 60))
        )
        coEvery { sagaTimeoutHandler.handle(order) } returns TimeoutOutcome.CANCELLATION_FAILED

        val result = webTestClient.post()
            .uri("/internal/saga/timeout/process")
            .exchange()
            .expectStatus().isOk
            .expectBody<TimeoutProcessResult>()
            .returnResult().responseBody!!

        result.processedCount shouldBe 1
        result.cancellationFailedOrders shouldContainExactly listOf("order-cancelling-001")

        coVerify(exactly = 1) { sagaTimeoutHandler.handle(order) }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 임계값 필터링 (SagaTimeoutService 인메모리 재필터링 검증)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `CANCELLING 주문이 45분 전 - 임계값(60분) 미달로 handler 미호출`() = runBlocking {
        insertStuckOrder(
            status     = OrderStatus.CANCELLING,
            minutesAgo = 45,
            sagaState  = null
        )

        webTestClient.post()
            .uri("/internal/saga/timeout/process")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.processedCount").isEqualTo(0)

        coVerify(exactly = 0) { sagaTimeoutHandler.handle(any()) }
    }

    @Test
    fun `최근 업데이트된 PENDING 주문(10분 전) - 임계값(30분) 미달로 handler 미호출`() = runBlocking {
        insertStuckOrder(
            status     = OrderStatus.PENDING,
            minutesAgo = 10
        )

        webTestClient.post()
            .uri("/internal/saga/timeout/process")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.processedCount").isEqualTo(0)

        coVerify(exactly = 0) { sagaTimeoutHandler.handle(any()) }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 복합 시나리오
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `여러 상태의 주문 혼재 - 각각 올바른 결과로 집계`() = runBlocking<Unit> {
        val pending  = insertStuckOrder("o-pending",  OrderStatus.PENDING,             minutesAgo = 60)
        val cancel   = insertStuckOrder("o-cancel",   OrderStatus.CANCELLING,          minutesAgo = 90,
            sagaState = null, cancellationStatus = CancellationState(Instant.now().minusSeconds(90 * 60)))
        val returnOrd = insertStuckOrder("o-return",  OrderStatus.RETURN_IN_PROGRESS,  minutesAgo = 150,
            sagaState = null)

        coEvery { sagaTimeoutHandler.handle(pending)   } returns TimeoutOutcome.CANCELLED
        coEvery { sagaTimeoutHandler.handle(cancel)    } returns TimeoutOutcome.CANCELLATION_FAILED
        coEvery { sagaTimeoutHandler.handle(returnOrd) } returns TimeoutOutcome.RETURN_FAILED

        val result = webTestClient.post()
            .uri("/internal/saga/timeout/process")
            .exchange()
            .expectStatus().isOk
            .expectBody<TimeoutProcessResult>()
            .returnResult().responseBody!!

        result.processedCount           shouldBe 3
        result.cancelledOrders          shouldContainExactly listOf("o-pending")
        result.cancellationFailedOrders shouldContainExactly listOf("o-cancel")
        result.returnFailedOrders       shouldContainExactly listOf("o-return")
        result.skippedOrders.shouldBeEmpty()
    }

    @Test
    fun `COMPENSATING 교착 주문 - skippedOrders 에 포함되고 processedCount 에 미포함`() = runBlocking {
        val order = insertStuckOrder(
            orderId    = "order-deadlock",
            status     = OrderStatus.PENDING,
            minutesAgo = 60,
            sagaState  = OrderSagaState(sagaId = "order-deadlock", currentStep = SagaStep.COMPENSATING)
        )
        coEvery { sagaTimeoutHandler.handle(order) } returns TimeoutOutcome.SKIPPED

        val result = webTestClient.post()
            .uri("/internal/saga/timeout/process")
            .exchange()
            .expectStatus().isOk
            .expectBody<TimeoutProcessResult>()
            .returnResult().responseBody!!

        result.processedCount shouldBe 0
        result.skippedOrders shouldContainExactly listOf("order-deadlock")
    }

    @Test
    fun `handler 예외 발생 - 해당 주문만 skipped, 나머지 정상 처리`() = runBlocking {
        val failOrder   = insertStuckOrder("o-fail",   OrderStatus.PENDING, minutesAgo = 60)
        val normalOrder = insertStuckOrder("o-normal", OrderStatus.PENDING, minutesAgo = 60)

        coEvery { sagaTimeoutHandler.handle(failOrder)   } throws RuntimeException("DB 연결 오류")
        coEvery { sagaTimeoutHandler.handle(normalOrder) } returns TimeoutOutcome.CANCELLED

        val result = webTestClient.post()
            .uri("/internal/saga/timeout/process")
            .exchange()
            .expectStatus().isOk
            .expectBody<TimeoutProcessResult>()
            .returnResult().responseBody!!

        result.processedCount shouldBe 1
        result.cancelledOrders shouldContainExactly listOf("o-normal")
        result.skippedOrders   shouldContainExactly listOf("o-fail")
    }

    // ════════════════════════════════════════════════════════════════════════
    // 멱등성
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `멱등성 - terminal 상태(CANCELLED)는 쿼리 대상에서 제외`() = runBlocking {
        val cancelledOrder = Orders(
            orderId         = "already-cancelled",
            userId          = "user-test",
            status          = OrderStatus.CANCELLED,
            items           = listOf(OrderItem("prod-001", 1, BigDecimal("10000"))),
            totalAmount     = BigDecimal("10000"),
            deliveryAddress = "서울시 강남구",
            receiverName    = "테스트",
            receiverPhone   = "010-0000-0000"
        )
        orderRepository.save(cancelledOrder)
        mongoTemplate.updateFirst(
            Query(Criteria.where("_id").`is`("already-cancelled")),
            Update().set("updatedAt", Instant.now().minusSeconds(999 * 60)),
            Orders::class.java
        ).awaitSingle()

        webTestClient.post()
            .uri("/internal/saga/timeout/process")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.processedCount").isEqualTo(0)

        coVerify(exactly = 0) { sagaTimeoutHandler.handle(any()) }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 응답 형식
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `응답 JSON 에 모든 필드가 포함된다`() {
        webTestClient.post()
            .uri("/internal/saga/timeout/process")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.processedCount").exists()
            .jsonPath("$.cancelledOrders").exists()
            .jsonPath("$.cancellationFailedOrders").exists()
            .jsonPath("$.returnFailedOrders").exists()
            .jsonPath("$.skippedOrders").exists()
    }
}
