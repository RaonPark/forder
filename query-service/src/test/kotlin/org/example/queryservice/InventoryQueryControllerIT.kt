package org.example.queryservice

import common.document.product.StockStatus
import kotlinx.coroutines.runBlocking
import org.example.queryservice.document.EnrichedInventory
import org.example.queryservice.repository.InventoryQueryRepository
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.data.elasticsearch.core.document.Document
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * InventoryQueryController 통합 테스트
 *
 * productName 필드가 korean_analyzer를 참조하므로
 * @BeforeAll에서 인덱스 1회 재생성 (korean_analyzer → standard)
 */
class InventoryQueryControllerIT : AbstractQueryIT() {

    @Autowired lateinit var webTestClient: WebTestClient
    @Autowired lateinit var inventoryRepository: InventoryQueryRepository
    @Autowired lateinit var elasticsearchOperations: ReactiveElasticsearchOperations

    // ─── 픽스처 ──────────────────────────────────────────────────

    private val inventory1 = EnrichedInventory(
        inventoryId   = "inv-1",
        productId     = "prod-1",
        optionId      = "opt-1",
        currentStock  = 100,
        reservedStock = 10,
        safetyStock   = 20,
        productName   = "삼성 갤럭시 노트북",
        category      = "전자기기",
        imageUrl      = "https://example.com/prod1.jpg",
        stockStatus   = StockStatus.NORMAL,
        location      = "WAREHOUSE_A"
    )

    private val inventory2 = EnrichedInventory(
        inventoryId   = "inv-2",
        productId     = "prod-1",
        optionId      = "opt-2",
        currentStock  = 5,
        reservedStock = 3,
        safetyStock   = 10,
        productName   = "삼성 갤럭시 노트북",
        category      = "전자기기",
        imageUrl      = null,
        stockStatus   = StockStatus.LOW,
        location      = "WAREHOUSE_B"
    )

    private val inventory3 = EnrichedInventory(
        inventoryId   = "inv-3",
        productId     = "prod-2",
        optionId      = null,
        currentStock  = 0,
        reservedStock = 0,
        safetyStock   = 5,
        productName   = "LG 에어컨",
        category      = "가전",
        imageUrl      = "https://example.com/prod2.jpg",
        stockStatus   = StockStatus.SOLD_OUT,
        location      = "WAREHOUSE_A"
    )

    @BeforeAll
    fun setUpIndex() {
        val indexOps = elasticsearchOperations.indexOps(EnrichedInventory::class.java)
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
        inventoryRepository.deleteAll().block()
        inventoryRepository.saveAll(listOf(inventory1, inventory2, inventory3)).collectList().block()
        elasticsearchOperations.indexOps(EnrichedInventory::class.java).refresh().block()
    } }

    // ════════════════════════════════════════════════════════════════════════
    // 단건 조회 GET /{inventoryId}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `재고 단건 조회 - 존재하는 inventoryId면 200과 재고 정보 반환`() {
        webTestClient.get()
            .uri("/v1/query/inventory/inv-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.inventoryId").isEqualTo("inv-1")
            .jsonPath("$.productId").isEqualTo("prod-1")
            .jsonPath("$.currentStock").isEqualTo(100)
            .jsonPath("$.stockStatus").isEqualTo("NORMAL")
            .jsonPath("$.location").isEqualTo("WAREHOUSE_A")
    }

    @Test
    fun `재고 단건 조회 - 존재하지 않는 inventoryId면 404 반환`() {
        webTestClient.get()
            .uri("/v1/query/inventory/non-existent")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
    }

    // ════════════════════════════════════════════════════════════════════════
    // 상품별 재고 GET /product/{productId}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `상품별 재고 조회 - productId에 해당하는 재고 목록 반환`() {
        webTestClient.get()
            .uri("/v1/query/inventory/product/prod-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[*].productId").value<List<String>> { ids ->
                assert(ids.all { it == "prod-1" }) { "prod-1 재고만 반환되어야 함" }
            }
    }

    @Test
    fun `상품별 재고 조회 - 재고 없으면 빈 배열 반환`() {
        webTestClient.get()
            .uri("/v1/query/inventory/product/non-existent-product")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(0)
    }

    // ════════════════════════════════════════════════════════════════════════
    // 재고 검색 GET /search
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `재고 검색 - stockStatus 필터로 SOLD_OUT 재고만 반환`() {
        webTestClient.get()
            .uri("/v1/query/inventory/search?stockStatus=SOLD_OUT")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(1)
            .jsonPath("$.content[0].stockStatus").isEqualTo("SOLD_OUT")
            .jsonPath("$.content[0].inventoryId").isEqualTo("inv-3")
    }

    @Test
    fun `재고 검색 - category 필터 적용`() {
        webTestClient.get()
            .uri("/v1/query/inventory/search?category=전자기기")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(2)
            .jsonPath("$.content[*].category").value<List<String>> { cats ->
                assert(cats.all { it == "전자기기" }) { "전자기기 재고만 반환되어야 함" }
            }
    }

    @Test
    fun `재고 검색 - keyword로 productName 검색`() {
        webTestClient.get()
            .uri("/v1/query/inventory/search?keyword=에어컨")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(1)
            .jsonPath("$.content[0].inventoryId").isEqualTo("inv-3")
    }

    @Test
    fun `재고 검색 - 조건에 맞는 재고 없으면 빈 페이지 반환`() {
        webTestClient.get()
            .uri("/v1/query/inventory/search?stockStatus=NORMAL&category=가전")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(0)
            .jsonPath("$.totalElements").isEqualTo(0)
    }
}
