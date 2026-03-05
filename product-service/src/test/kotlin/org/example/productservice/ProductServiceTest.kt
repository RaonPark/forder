package org.example.productservice

import common.document.ProductStatus
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.example.productservice.document.Products
import org.example.productservice.dto.CreateProductRequest
import org.example.productservice.dto.UpdateProductRequest
import org.example.productservice.dto.UpdateProductStatusRequest
import org.example.productservice.exception.ProductNotFoundException
import org.example.productservice.exception.ProductStatusConflictException
import org.example.productservice.repository.ProductRepository
import org.example.productservice.service.ProductService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

class ProductServiceTest {

    private val productRepository = mockk<ProductRepository>()
    private val service = ProductService(productRepository)

    @AfterEach
    fun tearDown() = clearAllMocks()

    // ─── 픽스처 ──────────────────────────────────────────────────

    private fun sampleProduct(
        productId: String = "prod-001",
        status: ProductStatus = ProductStatus.ON_SALE,
        isAvailable: Boolean = true
    ) = Products(
        productId   = productId,
        sellerId    = "seller-001",
        name        = "테스트 상품",
        description = "상품 설명",
        category    = "전자기기",
        brand       = "테스트브랜드",
        basePrice   = BigDecimal("50000"),
        salePrice   = BigDecimal("45000"),
        status      = status,
        isAvailable = isAvailable,
        imageUrl    = "https://example.com/image.jpg",
        tags        = listOf("신상품", "추천"),
        createdAt   = Instant.now()
    )

    private fun sampleCreateRequest() = CreateProductRequest(
        sellerId    = "seller-001",
        name        = "테스트 상품",
        description = "상품 설명",
        category    = "전자기기",
        brand       = "테스트브랜드",
        basePrice   = BigDecimal("50000"),
        salePrice   = BigDecimal("45000"),
        imageUrl    = "https://example.com/image.jpg",
        tags        = listOf("신상품", "추천")
    )

    // ════════════════════════════════════════════════════════════
    // createProduct
    // ════════════════════════════════════════════════════════════

    @Test
    fun `createProduct - 유효한 요청이면 ON_SALE 상태로 저장된다`() = runTest {
        val slot = slot<Products>()
        coEvery { productRepository.save(capture(slot)) } answers { slot.captured }

        val response = service.createProduct(sampleCreateRequest())

        assertNotNull(response.productId)
        assertEquals(ProductStatus.ON_SALE, response.status)
        assertTrue(response.isAvailable)
        assertEquals(BigDecimal("50000"), response.basePrice)
        assertEquals("seller-001", response.sellerId)
    }

    @Test
    fun `createProduct - productRepository는 정확히 1번만 호출된다`() = runTest {
        val slot = slot<Products>()
        coEvery { productRepository.save(capture(slot)) } answers { slot.captured }

        service.createProduct(sampleCreateRequest())

        coVerify(exactly = 1) { productRepository.save(any()) }
    }

    // ════════════════════════════════════════════════════════════
    // getProduct
    // ════════════════════════════════════════════════════════════

    @Test
    fun `getProduct - 존재하는 productId면 ProductResponse를 반환한다`() = runTest {
        coEvery { productRepository.findById("prod-001") } returns sampleProduct()

        val result = service.getProduct("prod-001")

        assertEquals("prod-001", result.productId)
        assertEquals("테스트 상품", result.name)
        assertEquals(ProductStatus.ON_SALE, result.status)
    }

    @Test
    fun `getProduct - 존재하지 않는 productId면 ProductNotFoundException이 발생한다`() = runTest {
        coEvery { productRepository.findById("not-exist") } returns null

        assertThrows<ProductNotFoundException> {
            service.getProduct("not-exist")
        }
    }

    // ════════════════════════════════════════════════════════════
    // getProductsBySeller
    // ════════════════════════════════════════════════════════════

    @Test
    fun `getProductsBySeller - sellerId에 해당하는 상품 목록을 반환한다`() = runTest {
        val products = listOf(
            sampleProduct("prod-001"),
            sampleProduct("prod-002")
        )
        coEvery {
            productRepository.findBySellerIdOrderByCreatedAtDesc("seller-001")
        } returns flowOf(*products.toTypedArray())

        val result = service.getProductsBySeller("seller-001").toList()

        assertEquals(2, result.size)
        assertEquals("prod-001", result[0].productId)
        assertEquals("prod-002", result[1].productId)
    }

    // ════════════════════════════════════════════════════════════
    // updateProduct
    // ════════════════════════════════════════════════════════════

    @Test
    fun `updateProduct - ON_SALE 상품은 정보를 수정할 수 있다`() = runTest {
        val product = sampleProduct(status = ProductStatus.ON_SALE)
        val updateSlot = slot<Products>()

        coEvery { productRepository.findById("prod-001") } returns product
        coEvery { productRepository.save(capture(updateSlot)) } answers { updateSlot.captured }

        val request = UpdateProductRequest(
            name        = "수정된 상품명",
            description = "수정된 설명",
            category    = "가전",
            brand       = "수정브랜드",
            basePrice   = BigDecimal("60000"),
            salePrice   = null,
            imageUrl    = "https://example.com/new.jpg",
            tags        = listOf("수정"),
            isAvailable = true
        )

        val result = service.updateProduct("prod-001", request)

        assertEquals("수정된 상품명", result.name)
        assertEquals(BigDecimal("60000"), result.basePrice)
        assertNull(result.salePrice)
    }

    @Test
    fun `updateProduct - DISCONTINUED 상품은 수정할 수 없다`() = runTest {
        coEvery { productRepository.findById("prod-001") } returns sampleProduct(status = ProductStatus.DISCONTINUED)

        assertThrows<ProductStatusConflictException> {
            service.updateProduct("prod-001", UpdateProductRequest(
                name = "x", description = "x", category = "x", brand = "x",
                basePrice = BigDecimal.ONE, salePrice = null, imageUrl = "x",
                tags = emptyList(), isAvailable = false
            ))
        }
    }

    @Test
    fun `updateProduct - 존재하지 않는 productId면 ProductNotFoundException이 발생한다`() = runTest {
        coEvery { productRepository.findById("not-exist") } returns null

        assertThrows<ProductNotFoundException> {
            service.updateProduct("not-exist", UpdateProductRequest(
                name = "x", description = "x", category = "x", brand = "x",
                basePrice = BigDecimal.ONE, salePrice = null, imageUrl = "x",
                tags = emptyList(), isAvailable = false
            ))
        }
    }

    // ════════════════════════════════════════════════════════════
    // changeStatus
    // ════════════════════════════════════════════════════════════

    @Test
    fun `changeStatus - ON_SALE에서 HIDDEN으로 변경하면 isAvailable이 false가 된다`() = runTest {
        val product = sampleProduct(status = ProductStatus.ON_SALE)
        val savedSlot = slot<Products>()

        coEvery { productRepository.findById("prod-001") } returns product
        coEvery { productRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = service.changeStatus("prod-001", UpdateProductStatusRequest(ProductStatus.HIDDEN))

        assertEquals(ProductStatus.HIDDEN, result.status)
        assertEquals(false, result.isAvailable)
    }

    @Test
    fun `changeStatus - HIDDEN에서 ON_SALE로 복구하면 isAvailable이 true가 된다`() = runTest {
        val product = sampleProduct(status = ProductStatus.HIDDEN, isAvailable = false)
        val savedSlot = slot<Products>()

        coEvery { productRepository.findById("prod-001") } returns product
        coEvery { productRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = service.changeStatus("prod-001", UpdateProductStatusRequest(ProductStatus.ON_SALE))

        assertEquals(ProductStatus.ON_SALE, result.status)
        assertEquals(true, result.isAvailable)
    }

    @Test
    fun `changeStatus - DISCONTINUED 상품은 상태를 변경할 수 없다 (최종 상태)`() = runTest {
        coEvery { productRepository.findById("prod-001") } returns sampleProduct(status = ProductStatus.DISCONTINUED)

        assertThrows<ProductStatusConflictException> {
            service.changeStatus("prod-001", UpdateProductStatusRequest(ProductStatus.ON_SALE))
        }
    }

    @Test
    fun `changeStatus - 존재하지 않는 productId면 ProductNotFoundException이 발생한다`() = runTest {
        coEvery { productRepository.findById("not-exist") } returns null

        assertThrows<ProductNotFoundException> {
            service.changeStatus("not-exist", UpdateProductStatusRequest(ProductStatus.HIDDEN))
        }
    }
}
