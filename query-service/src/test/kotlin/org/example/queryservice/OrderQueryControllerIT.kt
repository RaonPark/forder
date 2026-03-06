package org.example.queryservice

import common.document.delivery.DeliveryStatus
import common.document.order.OrderStatus
import common.document.payment.PaymentMethod
import kotlinx.coroutines.runBlocking
import org.example.queryservice.document.EnrichedItem
import org.example.queryservice.document.EnrichedOrders
import org.example.queryservice.repository.OrderQueryRepository
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.test.web.reactive.server.WebTestClient
import java.math.BigDecimal
import java.time.Instant

class OrderQueryControllerIT : AbstractQueryIT() {

    @Autowired lateinit var webTestClient: WebTestClient
    @Autowired lateinit var orderRepository: OrderQueryRepository
    @Autowired lateinit var elasticsearchOperations: ReactiveElasticsearchOperations

    // ─── 픽스처 ──────────────────────────────────────────────────

    private val item = EnrichedItem(
        productId   = "prod-1",
        quantity    = 2,
        price       = BigDecimal("25000"),
        productName = "테스트 상품",
        sellerName  = "테스트 셀러",
        imageUrl    = null
    )

    private val order1 = EnrichedOrders(
        orderId         = "order-1",
        userId          = "user-1",
        status          = OrderStatus.PAYMENT_COMPLETED,
        items           = listOf(item),
        totalAmount     = BigDecimal("50000"),
        deliveryAddress = "서울시 강남구",
        receiverName    = "홍길동",
        receiverPhone   = "010-1234-5678",
        paymentMethod   = PaymentMethod.CREDIT_CARD,
        createdAt       = Instant.now(),
        updatedAt       = Instant.now()
    )

    private val order2 = EnrichedOrders(
        orderId         = "order-2",
        userId          = "user-1",
        status          = OrderStatus.DELIVERED,
        items           = listOf(item),
        totalAmount     = BigDecimal("30000"),
        deliveryAddress = "서울시 서초구",
        receiverName    = "홍길동",
        receiverPhone   = "010-1234-5678",
        paymentMethod   = PaymentMethod.CREDIT_CARD,
        deliveryStatus  = DeliveryStatus.DELIVERED,
        createdAt       = Instant.now(),
        updatedAt       = Instant.now()
    )

    private val order3 = EnrichedOrders(
        orderId         = "order-3",
        userId          = "user-2",
        status          = OrderStatus.PREPARING,
        items           = listOf(item),
        totalAmount     = BigDecimal("15000"),
        deliveryAddress = "부산시 해운대구",
        receiverName    = "김철수",
        receiverPhone   = "010-9876-5432",
        paymentMethod   = PaymentMethod.BANK_TRANSFER,
        createdAt       = Instant.now(),
        updatedAt       = Instant.now()
    )

    @BeforeAll
    fun setUpIndex() {
        val indexOps = elasticsearchOperations.indexOps(EnrichedOrders::class.java)
        indexOps.delete().onErrorResume { reactor.core.publisher.Mono.just(false) }.block()
        indexOps.create().block()
        indexOps.putMapping(indexOps.createMapping()).block()
    }

    @BeforeEach
    fun setUp() { runBlocking {
        orderRepository.deleteAll().block()
        orderRepository.saveAll(listOf(order1, order2, order3)).collectList().block()
        elasticsearchOperations.indexOps(EnrichedOrders::class.java).refresh().block()
    } }

    // ════════════════════════════════════════════════════════════════════════
    // 단건 조회 GET /{orderId}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `주문 단건 조회 - 존재하는 orderId면 200과 주문 정보 반환`() {
        webTestClient.get()
            .uri("/v1/query/orders/order-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.orderId").isEqualTo("order-1")
            .jsonPath("$.userId").isEqualTo("user-1")
            .jsonPath("$.status").isEqualTo("PAYMENT_COMPLETED")
            .jsonPath("$.totalAmount").isEqualTo(50000)
            .jsonPath("$.paymentMethod").isEqualTo("CREDIT_CARD")
    }

    @Test
    fun `주문 단건 조회 - 존재하지 않는 orderId면 404 반환`() {
        webTestClient.get()
            .uri("/v1/query/orders/non-existent")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
    }

    @Test
    fun `주문 단건 조회 - 배송 상태 필드가 포함된 주문 정보 반환`() {
        webTestClient.get()
            .uri("/v1/query/orders/order-2")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.orderId").isEqualTo("order-2")
            .jsonPath("$.status").isEqualTo("DELIVERED")
            .jsonPath("$.deliveryStatus").isEqualTo("DELIVERED")
    }

    // ════════════════════════════════════════════════════════════════════════
    // 사용자별 목록 GET /user/{userId}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `사용자별 조회 - userId에 해당하는 주문만 반환`() {
        webTestClient.get()
            .uri("/v1/query/orders/user/user-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(2)
            .jsonPath("$.totalElements").isEqualTo(2)
            .jsonPath("$.page").isEqualTo(0)
            .jsonPath("$.size").isEqualTo(20)
            .jsonPath("$.content[*].userId").value<List<String>> { ids ->
                assert(ids.all { it == "user-1" }) { "user-1 주문만 반환되어야 함" }
            }
    }

    @Test
    fun `사용자별 조회 - status 파라미터로 필터링`() {
        webTestClient.get()
            .uri("/v1/query/orders/user/user-1?status=DELIVERED")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(1)
            .jsonPath("$.content[0].status").isEqualTo("DELIVERED")
    }

    @Test
    fun `사용자별 조회 - 존재하지 않는 userId면 빈 페이지 반환`() {
        webTestClient.get()
            .uri("/v1/query/orders/user/non-existent-user")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(0)
            .jsonPath("$.totalElements").isEqualTo(0)
    }

    @Test
    fun `사용자별 조회 - 페이지네이션 파라미터가 응답에 반영된다`() {
        webTestClient.get()
            .uri("/v1/query/orders/user/user-1?page=0&size=1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(1)
            .jsonPath("$.size").isEqualTo(1)
            .jsonPath("$.totalElements").isEqualTo(2)
            .jsonPath("$.totalPages").isEqualTo(2)
    }
}
