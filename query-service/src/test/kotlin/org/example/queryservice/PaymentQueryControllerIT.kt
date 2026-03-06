package org.example.queryservice

import common.document.payment.PaymentMethod
import common.document.payment.PaymentStatus
import common.document.payment.PgProvider
import kotlinx.coroutines.runBlocking
import org.example.queryservice.document.EnrichedPayment
import org.example.queryservice.repository.PaymentQueryRepository
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.test.web.reactive.server.WebTestClient
import java.math.BigDecimal
import java.time.Instant

/**
 * PaymentQueryController 통합 테스트
 *
 * payments-view 인덱스는 korean_analyzer 미사용 → @BeforeAll 인덱스 재생성 불필요
 */
class PaymentQueryControllerIT : AbstractQueryIT() {

    @Autowired lateinit var webTestClient: WebTestClient
    @Autowired lateinit var paymentRepository: PaymentQueryRepository
    @Autowired lateinit var elasticsearchOperations: ReactiveElasticsearchOperations

    // ─── 픽스처 ──────────────────────────────────────────────────

    private val payment1 = EnrichedPayment(
        paymentId      = "pay-1",
        orderId        = "order-1",
        userId         = "user-1",
        totalAmount    = BigDecimal("50000"),
        canceledAmount = BigDecimal("0"),
        status         = PaymentStatus.APPROVED,
        method         = PaymentMethod.CREDIT_CARD,
        pgTid          = "NICE-TID-001",
        pgProvider     = PgProvider.NICEPAY,
        createdAt      = Instant.now(),
        updatedAt      = Instant.now(),
        canceledAt     = null
    )

    private val payment2 = EnrichedPayment(
        paymentId      = "pay-2",
        orderId        = "order-2",
        userId         = "user-1",
        totalAmount    = BigDecimal("30000"),
        canceledAmount = BigDecimal("30000"),
        status         = PaymentStatus.CANCELED,
        method         = PaymentMethod.BANK_TRANSFER,
        pgTid          = "TOSS-TID-002",
        pgProvider     = PgProvider.TOSS_PAYMENTS,
        createdAt      = Instant.now().minusSeconds(3600),
        updatedAt      = Instant.now(),
        canceledAt     = Instant.now()
    )

    private val payment3 = EnrichedPayment(
        paymentId      = "pay-3",
        orderId        = "order-3",
        userId         = "user-2",
        totalAmount    = BigDecimal("15000"),
        canceledAmount = BigDecimal("0"),
        status         = PaymentStatus.APPROVED,
        method         = PaymentMethod.DIGITAL_WALLET,
        pgTid          = "KAKAO-TID-003",
        pgProvider     = PgProvider.KAKAO_PAY,
        createdAt      = Instant.now(),
        updatedAt      = Instant.now(),
        canceledAt     = null
    )

    @BeforeAll
    fun setUpIndex() {
        val indexOps = elasticsearchOperations.indexOps(EnrichedPayment::class.java)
        indexOps.delete().onErrorResume { reactor.core.publisher.Mono.just(false) }.block()
        indexOps.create().block()
        indexOps.putMapping(indexOps.createMapping()).block()
    }

    @BeforeEach
    fun setUp() { runBlocking {
        paymentRepository.deleteAll().block()
        paymentRepository.saveAll(listOf(payment1, payment2, payment3)).collectList().block()
        elasticsearchOperations.indexOps(EnrichedPayment::class.java).refresh().block()
    } }

    // ════════════════════════════════════════════════════════════════════════
    // 단건 조회 GET /{paymentId}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `결제 단건 조회 - 존재하는 paymentId면 200과 결제 정보 반환`() {
        webTestClient.get()
            .uri("/v1/query/payments/pay-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.paymentId").isEqualTo("pay-1")
            .jsonPath("$.orderId").isEqualTo("order-1")
            .jsonPath("$.userId").isEqualTo("user-1")
            .jsonPath("$.status").isEqualTo("APPROVED")
            .jsonPath("$.method").isEqualTo("CREDIT_CARD")
            .jsonPath("$.pgProvider").isEqualTo("NICEPAY")
            .jsonPath("$.totalAmount").isEqualTo(50000)
    }

    @Test
    fun `결제 단건 조회 - 존재하지 않는 paymentId면 404 반환`() {
        webTestClient.get()
            .uri("/v1/query/payments/non-existent")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
    }

    @Test
    fun `결제 단건 조회 - 취소된 결제의 canceledAt 및 canceledAmount 반환`() {
        webTestClient.get()
            .uri("/v1/query/payments/pay-2")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.paymentId").isEqualTo("pay-2")
            .jsonPath("$.status").isEqualTo("CANCELED")
            .jsonPath("$.canceledAmount").isEqualTo(30000)
            .jsonPath("$.canceledAt").isNotEmpty
    }

    // ════════════════════════════════════════════════════════════════════════
    // 주문별 조회 GET /order/{orderId}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `주문별 결제 조회 - orderId에 해당하는 결제 반환`() {
        webTestClient.get()
            .uri("/v1/query/payments/order/order-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.paymentId").isEqualTo("pay-1")
            .jsonPath("$.orderId").isEqualTo("order-1")
    }

    @Test
    fun `주문별 결제 조회 - 결제 없으면 404 반환`() {
        webTestClient.get()
            .uri("/v1/query/payments/order/non-existent-order")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
    }

    // ════════════════════════════════════════════════════════════════════════
    // 사용자별 조회 GET /user/{userId}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `사용자별 결제 조회 - userId에 해당하는 결제만 반환`() {
        webTestClient.get()
            .uri("/v1/query/payments/user/user-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(2)
            .jsonPath("$.totalElements").isEqualTo(2)
            .jsonPath("$.content[*].userId").value<List<String>> { ids ->
                assert(ids.all { it == "user-1" }) { "user-1 결제만 반환되어야 함" }
            }
    }

    @Test
    fun `사용자별 결제 조회 - 결제 없으면 빈 페이지 반환`() {
        webTestClient.get()
            .uri("/v1/query/payments/user/non-existent-user")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(0)
            .jsonPath("$.totalElements").isEqualTo(0)
    }

    @Test
    fun `사용자별 결제 조회 - 페이지네이션 적용`() {
        webTestClient.get()
            .uri("/v1/query/payments/user/user-1?page=0&size=1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(1)
            .jsonPath("$.size").isEqualTo(1)
            .jsonPath("$.totalElements").isEqualTo(2)
            .jsonPath("$.totalPages").isEqualTo(2)
    }
}
