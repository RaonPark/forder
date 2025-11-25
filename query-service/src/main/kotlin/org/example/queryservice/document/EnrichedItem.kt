package org.example.queryservice.document

import java.math.BigDecimal

data class EnrichedItem(
    val productId: String,
    val quantity: Int,
    val price: BigDecimal,
    val productName: String,
    val sellerName: String,
    val imageUrl: String?
)
