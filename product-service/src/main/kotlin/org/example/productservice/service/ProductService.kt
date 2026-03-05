package org.example.productservice.service

import common.document.ProductStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.example.productservice.document.Products
import org.example.productservice.dto.CreateProductRequest
import org.example.productservice.dto.ProductResponse
import org.example.productservice.dto.UpdateProductRequest
import org.example.productservice.dto.UpdateProductStatusRequest
import org.example.productservice.exception.ProductNotFoundException
import org.example.productservice.exception.ProductStatusConflictException
import org.example.productservice.repository.ProductRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProductService(
    private val productRepository: ProductRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // ──────────────────────────────────────────
    // 상품 등록
    // ──────────────────────────────────────────

    @Transactional
    suspend fun createProduct(request: CreateProductRequest): ProductResponse {
        val productId = UUID.randomUUID().toString()

        val product = Products(
            productId   = productId,
            sellerId    = request.sellerId,
            name        = request.name,
            description = request.description,
            category    = request.category,
            brand       = request.brand,
            basePrice   = request.basePrice,
            salePrice   = request.salePrice,
            status      = ProductStatus.ON_SALE,
            isAvailable = true,
            imageUrl    = request.imageUrl,
            tags        = request.tags
        )

        val saved = productRepository.save(product)
        log.info("[Product] Created - productId={}", productId)
        return ProductResponse.from(saved)
    }

    // ──────────────────────────────────────────
    // 상품 단건 조회
    // ──────────────────────────────────────────

    suspend fun getProduct(productId: String): ProductResponse =
        ProductResponse.from(findProductOrThrow(productId))

    // ──────────────────────────────────────────
    // 판매자별 상품 목록
    // ──────────────────────────────────────────

    fun getProductsBySeller(sellerId: String): Flow<ProductResponse> =
        productRepository.findBySellerIdOrderByCreatedAtDesc(sellerId)
            .map { ProductResponse.from(it) }

    fun getProductsBySellerAndStatus(sellerId: String, status: ProductStatus): Flow<ProductResponse> =
        productRepository.findBySellerIdAndStatusOrderByCreatedAtDesc(sellerId, status)
            .map { ProductResponse.from(it) }

    // ──────────────────────────────────────────
    // 상품 정보 수정
    // 도메인 불변식: DISCONTINUED 상품은 수정 불가
    // ──────────────────────────────────────────

    @Transactional
    suspend fun updateProduct(productId: String, request: UpdateProductRequest): ProductResponse {
        val product = findProductOrThrow(productId)

        if (product.status == ProductStatus.DISCONTINUED) {
            throw ProductStatusConflictException(
                "Cannot update a discontinued product. productId=$productId"
            )
        }

        val updated = product.copy(
            name        = request.name,
            description = request.description,
            category    = request.category,
            brand       = request.brand,
            basePrice   = request.basePrice,
            salePrice   = request.salePrice,
            imageUrl    = request.imageUrl,
            tags        = request.tags,
            isAvailable = request.isAvailable
        )

        log.info("[Product] Updated - productId={}", productId)
        return ProductResponse.from(productRepository.save(updated))
    }

    // ──────────────────────────────────────────
    // 상태 변경
    // 도메인 불변식: DISCONTINUED는 최종 상태 — 전이 불가
    // ──────────────────────────────────────────

    @Transactional
    suspend fun changeStatus(productId: String, request: UpdateProductStatusRequest): ProductResponse {
        val product = findProductOrThrow(productId)

        if (product.status == ProductStatus.DISCONTINUED) {
            throw ProductStatusConflictException(
                "Cannot change status of a discontinued product. productId=$productId"
            )
        }

        val updated = product.copy(
            status      = request.status,
            isAvailable = request.status == ProductStatus.ON_SALE
        )

        log.info("[Product] Status changed - productId={}, {} -> {}", productId, product.status, request.status)
        return ProductResponse.from(productRepository.save(updated))
    }

    // ──────────────────────────────────────────
    // 내부 헬퍼
    // ──────────────────────────────────────────

    private suspend fun findProductOrThrow(productId: String): Products =
        productRepository.findById(productId)
            ?: throw ProductNotFoundException(productId)
}
