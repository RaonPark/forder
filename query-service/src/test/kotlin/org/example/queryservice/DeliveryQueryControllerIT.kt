package org.example.queryservice

import common.document.delivery.DeliveryStatus
import common.document.delivery.EnrichedDeliveryItem
import kotlinx.coroutines.runBlocking
import org.example.queryservice.document.EnrichedDelivery
import org.example.queryservice.repository.DeliveryQueryRepository
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.data.elasticsearch.core.document.Document
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

/**
 * DeliveryQueryController 통합 테스트
 *
 * recipientName 필드가 korean_analyzer를 참조하므로
 * @BeforeAll에서 인덱스 1회 재생성 (korean_analyzer → standard)
 */
class DeliveryQueryControllerIT : AbstractQueryIT() {

    @Autowired lateinit var webTestClient: WebTestClient
    @Autowired lateinit var deliveryRepository: DeliveryQueryRepository
    @Autowired lateinit var elasticsearchOperations: ReactiveElasticsearchOperations

    // ─── 픽스처 ──────────────────────────────────────────────────

    private val deliveryItem = EnrichedDeliveryItem(
        productId   = "prod-1",
        productName = "테스트 상품",
        quantity    = 2,
        imageUrl    = null
    )

    private val delivery1 = EnrichedDelivery(
        deliveryId       = "del-1",
        orderId          = "order-1",
        userId           = "user-1",
        trackingNumber   = "TRK-ABCD1234",
        recipientName    = "홍길동",
        items            = listOf(deliveryItem),
        status           = DeliveryStatus.IN_TRANSIT,
        courierId        = "courier-1",
        courierName      = "CJ대한통운",
        currentLocation  = "서울 강남 허브",
        fullTrackingUrl  = null,
        lastDescription  = "배송 중입니다",
        startedAt        = Instant.now(),
        estimatedArrival = null,
        deliveredAt      = null
    )

    private val delivery2 = EnrichedDelivery(
        deliveryId       = "del-2",
        orderId          = "order-2",
        userId           = "user-1",
        trackingNumber   = "TRK-EFGH5678",
        recipientName    = "홍길동",
        items            = listOf(deliveryItem),
        status           = DeliveryStatus.DELIVERED,
        courierId        = "courier-2",
        courierName      = "한진택배",
        currentLocation  = null,
        fullTrackingUrl  = null,
        lastDescription  = "배달 완료",
        startedAt        = Instant.now().minusSeconds(86400),
        estimatedArrival = null,
        deliveredAt      = Instant.now()
    )

    private val delivery3 = EnrichedDelivery(
        deliveryId       = "del-3",
        orderId          = "order-3",
        userId           = "user-2",
        trackingNumber   = null,
        recipientName    = "김철수",
        items            = listOf(deliveryItem),
        status           = DeliveryStatus.PENDING,
        courierId        = null,
        courierName      = null,
        currentLocation  = null,
        fullTrackingUrl  = null,
        lastDescription  = null,
        startedAt        = null,
        estimatedArrival = null,
        deliveredAt      = null
    )

    @BeforeAll
    fun setUpIndex() {
        val indexOps = elasticsearchOperations.indexOps(EnrichedDelivery::class.java)
        indexOps.delete().onErrorResume { reactor.core.publisher.Mono.just(false) }.block()

        val settings = Document.from(mapOf(
            "analysis" to mapOf(
                "analyzer" to mapOf(
                    "korean_analyzer" to mapOf("type" to "standard")
                )
            )
        ))
        indexOps.create(settings).block()
        indexOps.putMapping(indexOps.createMapping()).block()
    }

    @BeforeEach
    fun setUp() { runBlocking {
        deliveryRepository.deleteAll().block()
        deliveryRepository.saveAll(listOf(delivery1, delivery2, delivery3)).collectList().block()
        elasticsearchOperations.indexOps(EnrichedDelivery::class.java).refresh().block()
    } }

    // ════════════════════════════════════════════════════════════════════════
    // 단건 조회 GET /{deliveryId}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `배송 단건 조회 - 존재하는 deliveryId면 200과 배송 정보 반환`() {
        webTestClient.get()
            .uri("/v1/query/deliveries/del-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.deliveryId").isEqualTo("del-1")
            .jsonPath("$.orderId").isEqualTo("order-1")
            .jsonPath("$.userId").isEqualTo("user-1")
            .jsonPath("$.trackingNumber").isEqualTo("TRK-ABCD1234")
            .jsonPath("$.status").isEqualTo("IN_TRANSIT")
            .jsonPath("$.courierName").isEqualTo("CJ대한통운")
    }

    @Test
    fun `배송 단건 조회 - 존재하지 않는 deliveryId면 404 반환`() {
        webTestClient.get()
            .uri("/v1/query/deliveries/non-existent")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
    }

    // ════════════════════════════════════════════════════════════════════════
    // 주문별 조회 GET /order/{orderId}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `주문별 배송 조회 - orderId에 해당하는 배송 반환`() {
        webTestClient.get()
            .uri("/v1/query/deliveries/order/order-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.deliveryId").isEqualTo("del-1")
            .jsonPath("$.orderId").isEqualTo("order-1")
            .jsonPath("$.status").isEqualTo("IN_TRANSIT")
    }

    @Test
    fun `주문별 배송 조회 - 배송 없으면 404 반환`() {
        webTestClient.get()
            .uri("/v1/query/deliveries/order/non-existent-order")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
    }

    // ════════════════════════════════════════════════════════════════════════
    // 사용자별 조회 GET /user/{userId}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `사용자별 배송 조회 - userId에 해당하는 배송만 반환`() {
        webTestClient.get()
            .uri("/v1/query/deliveries/user/user-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(2)
            .jsonPath("$.totalElements").isEqualTo(2)
            .jsonPath("$.content[*].userId").value<List<String>> { ids ->
                assert(ids.all { it == "user-1" }) { "user-1 배송만 반환되어야 함" }
            }
    }

    @Test
    fun `사용자별 배송 조회 - 배송 없으면 빈 페이지 반환`() {
        webTestClient.get()
            .uri("/v1/query/deliveries/user/non-existent-user")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(0)
            .jsonPath("$.totalElements").isEqualTo(0)
    }

    @Test
    fun `사용자별 배송 조회 - 배달 완료 정보가 정확히 반환된다`() {
        webTestClient.get()
            .uri("/v1/query/deliveries/user/user-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content[?(@.deliveryId == 'del-2')].status").isEqualTo("DELIVERED")
            .jsonPath("$.content[?(@.deliveryId == 'del-2')].deliveredAt").isNotEmpty
    }
}
