package org.example.productservice.document

import common.document.product.ProductStatus
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant

@Document(collection = "products")
@CompoundIndexes(
    CompoundIndex(name = "idx_products_sellerId_status", def = "{'sellerId': 1, 'status': 1}")
)
data class Products(
    @Id
    val productId: String,

    val name: String,
    val description: String,
    val category: String,
    val brand: String,
    val sellerId: String,

    val basePrice: BigDecimal,
    val salePrice: BigDecimal? = null,

    var status: ProductStatus,
    var isAvailable: Boolean,

    val imageUrl: String,
    val tags: List<String>,

    @CreatedDate
    val createdAt: Instant? = null,
    @LastModifiedDate
    val updatedAt: Instant? = null,
    @Version
    val version: Long? = null
)
