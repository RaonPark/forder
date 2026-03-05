package org.example.productservice.dto

import common.document.ProductStatus
import org.example.productservice.document.Products
import java.math.BigDecimal
import java.time.Instant

data class CreateProductRequest(
    val sellerId: String,
    val name: String,
    val description: String,
    val category: String,
    val brand: String,
    val basePrice: BigDecimal,
    val salePrice: BigDecimal? = null,
    val imageUrl: String,
    val tags: List<String> = emptyList()
)

data class UpdateProductRequest(
    val name: String,
    val description: String,
    val category: String,
    val brand: String,
    val basePrice: BigDecimal,
    val salePrice: BigDecimal? = null,
    val imageUrl: String,
    val tags: List<String> = emptyList(),
    val isAvailable: Boolean
)

data class UpdateProductStatusRequest(
    val status: ProductStatus
)

data class ProductResponse(
    val productId: String,
    val sellerId: String,
    val name: String,
    val description: String,
    val category: String,
    val brand: String,
    val basePrice: BigDecimal,
    val salePrice: BigDecimal?,
    val status: ProductStatus,
    val isAvailable: Boolean,
    val imageUrl: String,
    val tags: List<String>,
    val createdAt: Instant?,
    val updatedAt: Instant?
) {
    companion object {
        fun from(product: Products) = ProductResponse(
            productId   = product.productId,
            sellerId    = product.sellerId,
            name        = product.name,
            description = product.description,
            category    = product.category,
            brand       = product.brand,
            basePrice   = product.basePrice,
            salePrice   = product.salePrice,
            status      = product.status,
            isAvailable = product.isAvailable,
            imageUrl    = product.imageUrl,
            tags        = product.tags,
            createdAt   = product.createdAt,
            updatedAt   = product.updatedAt
        )
    }
}
