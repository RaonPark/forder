package org.example.queryservice

import common.document.product.ProductStatus
import kotlinx.coroutines.runBlocking
import org.example.queryservice.document.EnrichedProducts
import org.example.queryservice.repository.ProductQueryRepository
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.data.elasticsearch.core.document.Document
import org.springframework.test.web.reactive.server.WebTestClient
import java.math.BigDecimal

/**
 * ProductQueryController 통합 테스트
 *
 * ─── korean_analyzer 처리 ───
 * fullTextSearch 필드가 korean_analyzer를 참조한다.
 * 테스트 컨테이너에는 analysis-nori 플러그인이 없으므로,
 * @BeforeAll에서 인덱스를 1회 재생성하여 korean_analyzer → standard 로 aliasing한다.
 * 이후 @BeforeEach에서는 문서만 초기화하므로 index 재생성 비용이 없다.
 *
 * ─── 테스트 생명주기 ───
 * @BeforeAll : 인덱스 재생성 (korean_analyzer=standard 적용) — 클래스당 1회
 * @BeforeEach: 문서 전체 삭제 → 픽스처 저장 → refresh
 */
class ProductQueryControllerIT : AbstractQueryIT() {

    @Autowired lateinit var webTestClient: WebTestClient
    @Autowired lateinit var productRepository: ProductQueryRepository
    @Autowired lateinit var elasticsearchOperations: ReactiveElasticsearchOperations

    // ─── 픽스처 ──────────────────────────────────────────────────

    private val product1 = EnrichedProducts(
        productId        = "prod-1",
        name             = "삼성 갤럭시 노트북",
        salePrice        = BigDecimal("1200000"),
        imageUrl         = "https://example.com/prod1.jpg",
        category         = "전자기기",
        status           = ProductStatus.ON_SALE,
        tags             = listOf("노트북", "삼성"),
        sellerName       = "삼성전자",
        averageRating    = 4.5,
        reviewCount      = 100,
        fullTextSearch   = "노트북 삼성 갤럭시 전자기기",
        priceRangeBucket = "HIGH"
    )

    private val product2 = EnrichedProducts(
        productId        = "prod-2",
        name             = "LG 에어컨",
        salePrice        = BigDecimal("800000"),
        imageUrl         = "https://example.com/prod2.jpg",
        category         = "가전",
        status           = ProductStatus.ON_SALE,
        tags             = listOf("에어컨", "LG"),
        sellerName       = "LG전자",
        averageRating    = 4.2,
        reviewCount      = 50,
        fullTextSearch   = "에어컨 LG 가전",
        priceRangeBucket = "HIGH"
    )

    private val product3 = EnrichedProducts(
        productId        = "prod-3",
        name             = "애플 아이폰",
        salePrice        = BigDecimal("1500000"),
        imageUrl         = "https://example.com/prod3.jpg",
        category         = "전자기기",
        status           = ProductStatus.HIDDEN,
        tags             = listOf("스마트폰", "애플"),
        sellerName       = "애플코리아",
        averageRating    = 4.8,
        reviewCount      = 200,
        fullTextSearch   = "아이폰 애플 스마트폰 전자기기",
        priceRangeBucket = "HIGH"
    )

    @BeforeAll
    fun setUpIndex() {
        val indexOps = elasticsearchOperations.indexOps(EnrichedProducts::class.java)
        // 기존 인덱스 삭제 (없을 경우 에러 무시)
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
        productRepository.deleteAll().block()
        productRepository.saveAll(listOf(product1, product2, product3)).collectList().block()
        elasticsearchOperations.indexOps(EnrichedProducts::class.java).refresh().block()
    } }

    // ════════════════════════════════════════════════════════════════════════
    // 단건 조회 GET /{productId}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `상품 단건 조회 - 존재하는 productId면 200과 상품 정보 반환`() {
        webTestClient.get()
            .uri("/v1/query/products/prod-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.productId").isEqualTo("prod-1")
            .jsonPath("$.name").isEqualTo("삼성 갤럭시 노트북")
            .jsonPath("$.category").isEqualTo("전자기기")
            .jsonPath("$.status").isEqualTo("ON_SALE")
            .jsonPath("$.sellerName").isEqualTo("삼성전자")
    }

    @Test
    fun `상품 단건 조회 - 존재하지 않는 productId면 404 반환`() {
        webTestClient.get()
            .uri("/v1/query/products/non-existent")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
    }

    // ════════════════════════════════════════════════════════════════════════
    // 상품 검색 GET /search
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `상품 검색 - keyword로 fullTextSearch 필드를 검색`() {
        webTestClient.get()
            .uri("/v1/query/products/search?keyword=노트북")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(1)
            .jsonPath("$.content[0].productId").isEqualTo("prod-1")
    }

    @Test
    fun `상품 검색 - category 필터 적용`() {
        webTestClient.get()
            .uri("/v1/query/products/search?category=전자기기")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(2)
            .jsonPath("$.content[*].category").value<List<String>> { cats ->
                assert(cats.all { it == "전자기기" }) { "전자기기 상품만 반환되어야 함" }
            }
    }

    @Test
    fun `상품 검색 - status 필터로 ON_SALE 상품만 반환`() {
        webTestClient.get()
            .uri("/v1/query/products/search?status=ON_SALE")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(2)
            .jsonPath("$.content[*].status").value<List<String>> { statuses ->
                assert(statuses.all { it == "ON_SALE" }) { "ON_SALE 상품만 반환되어야 함" }
            }
    }

    @Test
    fun `상품 검색 - keyword와 category 복합 필터`() {
        webTestClient.get()
            .uri("/v1/query/products/search?keyword=아이폰&category=전자기기")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(1)
            .jsonPath("$.content[0].productId").isEqualTo("prod-3")
    }

    @Test
    fun `상품 검색 - 조건에 맞는 상품 없으면 빈 페이지 반환`() {
        webTestClient.get()
            .uri("/v1/query/products/search?category=의류")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(0)
            .jsonPath("$.totalElements").isEqualTo(0)
    }

    @Test
    fun `상품 검색 - 페이지네이션 적용`() {
        webTestClient.get()
            .uri("/v1/query/products/search?page=0&size=2")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(2)
            .jsonPath("$.size").isEqualTo(2)
            .jsonPath("$.totalElements").isEqualTo(3)
            .jsonPath("$.totalPages").isEqualTo(2)
    }

    // ════════════════════════════════════════════════════════════════════════
    // 셀러별 조회 GET /seller/{sellerName}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `셀러별 조회 - sellerName에 해당하는 상품만 반환`() {
        webTestClient.get()
            .uri("/v1/query/products/seller/삼성전자")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(1)
            .jsonPath("$.content[0].sellerName").isEqualTo("삼성전자")
    }

    @Test
    fun `셀러별 조회 - 상품 없으면 빈 페이지 반환`() {
        webTestClient.get()
            .uri("/v1/query/products/seller/없는셀러")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(0)
    }
}
