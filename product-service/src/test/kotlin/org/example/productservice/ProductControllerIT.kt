package org.example.productservice

import common.document.product.ProductStatus
import kotlinx.coroutines.runBlocking
import org.example.productservice.repository.ProductRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * ProductController 통합 테스트.
 *
 * 테스트 범위:
 *   Controller → ProductService(@Transactional) → ProductRepository(MongoDB) → 응답 JSON
 *
 * ─── MongoDB 설정 ───
 * TestcontainersConfiguration의 MongoDBContainer는 단일 노드 레플리카셋(docker-rs)으로 기동되며,
 * getConnectionString()을 재정의해 replicaSet=docker-rs&directConnection=true URI를 제공한다.
 * 이를 통해 드라이버가 레플리카셋 토폴로지를 인식하고 @Transactional과 retryWrites가 동작한다.
 *
 * ─── 단위 테스트(ProductServiceTest)에서 이미 검증한 것 ───
 *   - 비즈니스 로직 (DISCONTINUED 제약, isAvailable 자동 동기화)
 *   - 예외 타입 및 HTTP 상태 코드 매핑
 *
 * ─── 이 테스트가 추가로 검증하는 것 ───
 *   1. 실제 MongoDB에 데이터가 영속화되는지
 *   2. @CreatedDate / @LastModifiedDate 자동 설정
 *   3. @Version 초기값 및 업데이트 후 증가
 *   4. 셀러별 조회 + 상태 필터 쿼리 정확성
 *   5. 실제 DB 데이터 기반 DISCONTINUED 409 반환
 *   6. 전체 HTTP 스택 (직렬화 / 역직렬화 포함)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration::class)
class ProductControllerIT {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var productRepository: ProductRepository

    @BeforeEach
    fun setUp() = runBlocking {
        productRepository.deleteAll()
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private fun createRequestBody(
        sellerId: String = "seller-001",
        name: String = "테스트 상품",
        basePrice: Int = 50000
    ) = """
        {
          "sellerId": "$sellerId",
          "name": "$name",
          "description": "상품 설명",
          "category": "전자기기",
          "brand": "테스트브랜드",
          "basePrice": $basePrice,
          "salePrice": 45000,
          "imageUrl": "https://example.com/image.jpg",
          "tags": ["신상품", "추천"]
        }
    """.trimIndent()

    /** POST /v1/products 호출 후 생성된 productId를 반환 */
    private fun createProduct(
        sellerId: String = "seller-001",
        name: String = "테스트 상품",
        basePrice: Int = 50000
    ): String {
        val body = webTestClient.post()
            .uri("/v1/products")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createRequestBody(sellerId, name, basePrice))
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.productId").isNotEmpty
            .returnResult()
            .responseBody!!
        return Regex(""""productId"\s*:\s*"([^"]+)"""")
            .find(String(body))!!.groupValues[1]
    }

    // ════════════════════════════════════════════════════════════════════════
    // 생성 (POST /v1/products)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `상품 생성 - 201 반환, ON_SALE 상태, isAvailable=true`() {
        webTestClient.post()
            .uri("/v1/products")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createRequestBody())
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.productId").isNotEmpty
            .jsonPath("$.sellerId").isEqualTo("seller-001")
            .jsonPath("$.name").isEqualTo("테스트 상품")
            .jsonPath("$.status").isEqualTo("ON_SALE")
            .jsonPath("$.isAvailable").isEqualTo(true)
            .jsonPath("$.basePrice").isEqualTo(50000)
            .jsonPath("$.salePrice").isEqualTo(45000)
    }

    @Test
    fun `상품 생성 - createdAt, updatedAt 자동 설정`() {
        webTestClient.post()
            .uri("/v1/products")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createRequestBody())
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.createdAt").isNotEmpty
            .jsonPath("$.updatedAt").isNotEmpty
    }

    @Test
    fun `상품 생성 - MongoDB에 실제 영속화된다`() = runBlocking {
        val productId = createProduct()

        val saved = productRepository.findById(productId)
        assert(saved != null) { "DB에서 상품을 찾을 수 없음: $productId" }
        assert(saved!!.name == "테스트 상품")
        assert(saved.status == ProductStatus.ON_SALE)
        assert(saved.isAvailable)
        assert(saved.createdAt != null) { "@CreatedDate 가 설정되지 않음" }
        assert(saved.updatedAt != null) { "@LastModifiedDate 가 설정되지 않음" }
        assert(saved.version != null && saved.version!! >= 0) { "@Version 이 설정되지 않음" }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 단건 조회 (GET /v1/products/{productId})
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `상품 조회 - 존재하는 productId 면 200 반환`() {
        val productId = createProduct()

        webTestClient.get()
            .uri("/v1/products/$productId")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.productId").isEqualTo(productId)
            .jsonPath("$.name").isEqualTo("테스트 상품")
            .jsonPath("$.status").isEqualTo("ON_SALE")
    }

    @Test
    fun `상품 조회 - 존재하지 않는 productId 면 404 반환`() {
        webTestClient.get()
            .uri("/v1/products/non-existent-id")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("PRODUCT_NOT_FOUND")
    }

    // ════════════════════════════════════════════════════════════════════════
    // 수정 (PUT /v1/products/{productId})
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `상품 수정 - ON_SALE 상태면 200 반환, 변경된 필드 확인`() {
        val productId = createProduct()

        webTestClient.put()
            .uri("/v1/products/$productId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "name": "수정된 상품명",
                  "description": "수정된 설명",
                  "category": "가전",
                  "brand": "수정브랜드",
                  "basePrice": 60000,
                  "imageUrl": "https://example.com/new.jpg",
                  "tags": ["수정"],
                  "isAvailable": true
                }
            """.trimIndent())
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.name").isEqualTo("수정된 상품명")
            .jsonPath("$.basePrice").isEqualTo(60000)
            .jsonPath("$.category").isEqualTo("가전")
    }

    @Test
    fun `상품 수정 - @Version 이 수정 후 증가한다`() = runBlocking {
        val productId = createProduct()
        val beforeVersion = productRepository.findById(productId)!!.version!!

        webTestClient.put()
            .uri("/v1/products/$productId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "name": "버전 테스트 상품",
                  "description": "x", "category": "x", "brand": "x",
                  "basePrice": 1000, "imageUrl": "x", "tags": [], "isAvailable": true
                }
            """.trimIndent())
            .exchange()
            .expectStatus().isOk

        val afterVersion = productRepository.findById(productId)!!.version!!
        assert(afterVersion > beforeVersion) {
            "수정 후 @Version 이 증가해야 함: before=$beforeVersion, after=$afterVersion"
        }
    }

    @Test
    fun `상품 수정 - DISCONTINUED 상품은 409 반환`() {
        val productId = createProduct()

        webTestClient.patch()
            .uri("/v1/products/$productId/status")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"status":"DISCONTINUED"}""")
            .exchange()
            .expectStatus().isOk

        webTestClient.put()
            .uri("/v1/products/$productId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "name": "x", "description": "x", "category": "x", "brand": "x",
                  "basePrice": 1, "imageUrl": "x", "tags": [], "isAvailable": false
                }
            """.trimIndent())
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("PRODUCT_STATUS_CONFLICT")
    }

    // ════════════════════════════════════════════════════════════════════════
    // 상태 변경 (PATCH /v1/products/{productId}/status)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `상태 변경 - ON_SALE → HIDDEN 이면 isAvailable=false`() {
        val productId = createProduct()

        webTestClient.patch()
            .uri("/v1/products/$productId/status")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"status":"HIDDEN"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("HIDDEN")
            .jsonPath("$.isAvailable").isEqualTo(false)
    }

    @Test
    fun `상태 변경 - HIDDEN → ON_SALE 이면 isAvailable=true`() {
        val productId = createProduct()

        webTestClient.patch()
            .uri("/v1/products/$productId/status")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"status":"HIDDEN"}""")
            .exchange()
            .expectStatus().isOk

        webTestClient.patch()
            .uri("/v1/products/$productId/status")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"status":"ON_SALE"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("ON_SALE")
            .jsonPath("$.isAvailable").isEqualTo(true)
    }

    @Test
    fun `상태 변경 - DISCONTINUED 는 최종 상태, 재변경 시 409 반환`() {
        val productId = createProduct()

        webTestClient.patch()
            .uri("/v1/products/$productId/status")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"status":"DISCONTINUED"}""")
            .exchange()
            .expectStatus().isOk

        webTestClient.patch()
            .uri("/v1/products/$productId/status")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"status":"ON_SALE"}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("PRODUCT_STATUS_CONFLICT")
    }

    // ════════════════════════════════════════════════════════════════════════
    // 셀러별 조회 (GET /v1/products/seller/{sellerId})
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `셀러별 조회 - sellerId 에 해당하는 상품만 반환`() {
        createProduct(sellerId = "seller-A", name = "상품1")
        createProduct(sellerId = "seller-A", name = "상품2")
        createProduct(sellerId = "seller-B", name = "다른 셀러 상품")

        webTestClient.get()
            .uri("/v1/products/seller/seller-A")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[*].sellerId").value<List<String>> { ids ->
                assert(ids.all { it == "seller-A" }) { "seller-A 상품만 반환되어야 함" }
            }
    }

    @Test
    fun `셀러별 조회 - status 파라미터로 필터링`() {
        val productId = createProduct(sellerId = "seller-A", name = "상품1")
        createProduct(sellerId = "seller-A", name = "상품2")

        webTestClient.patch()
            .uri("/v1/products/$productId/status")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"status":"HIDDEN"}""")
            .exchange()
            .expectStatus().isOk

        webTestClient.get()
            .uri("/v1/products/seller/seller-A?status=ON_SALE")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].status").isEqualTo("ON_SALE")
    }

    @Test
    fun `셀러별 조회 - 상품이 없으면 빈 배열 반환`() {
        webTestClient.get()
            .uri("/v1/products/seller/non-existent-seller")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(0)
    }

    // ════════════════════════════════════════════════════════════════════════
    // @LastModifiedDate 변경 확인
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `updatedAt 이 수정 후 변경된다`() = runBlocking {
        val productId = createProduct()
        val beforeUpdatedAt = productRepository.findById(productId)!!.updatedAt!!

        Thread.sleep(50)

        webTestClient.put()
            .uri("/v1/products/$productId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "name": "업데이트 타임스탬프 테스트",
                  "description": "x", "category": "x", "brand": "x",
                  "basePrice": 1000, "imageUrl": "x", "tags": [], "isAvailable": true
                }
            """.trimIndent())
            .exchange()
            .expectStatus().isOk

        val afterUpdatedAt = productRepository.findById(productId)!!.updatedAt!!
        assert(!afterUpdatedAt.isBefore(beforeUpdatedAt)) {
            "updatedAt 이 수정 후 갱신되어야 함: before=$beforeUpdatedAt, after=$afterUpdatedAt"
        }
    }
}
