package org.example.queryservice.document

import common.document.product.ProductStatus
import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import java.math.BigDecimal

@Document(indexName = "products-view")
data class EnrichedProducts(
    @Id
    val productId: String,

    val name: String,
    val salePrice: BigDecimal,
    val imageUrl: String,
    @Field(type = FieldType.Keyword)
    val category: String,
    @Field(type = FieldType.Keyword)
    val status: ProductStatus,
    @Field(type = FieldType.Keyword)
    val tags: List<String>,

    @Field(type = FieldType.Keyword)
    val sellerName: String,
    val averageRating: Double = 0.0,
    val reviewCount: Long = 0,
    val currentSalesCount: Long = 0,

    @Field(type = FieldType.Text, analyzer = "korean_analyzer", searchAnalyzer = "korean_analyzer")
    val fullTextSearch: String,
    @Field(type = FieldType.Keyword)
    val priceRangeBucket: String
)