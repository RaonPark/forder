package org.example.queryservice.document

import common.document.product.StockStatus
import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType

@Document(indexName = "inventory-view")
data class EnrichedInventory(
    @Id
    val inventoryId: String,

    val productId: String,

    @Field(type = FieldType.Keyword)
    val optionId: String? = null,

    val currentStock: Int,
    val reservedStock: Int,
    val safetyStock: Int,

    @Field(type = FieldType.Text, analyzer = "korean_analyzer", searchAnalyzer = "korean_analyzer")
    val productName: String,

    @Field(type = FieldType.Keyword)
    val category: String,

    val imageUrl: String?,

    @Field(type = FieldType.Keyword)
    val stockStatus: StockStatus,

    @Field(type = FieldType.Keyword)
    val location: String
)