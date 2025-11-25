package org.example.orderservice.document

import java.math.BigDecimal

data class OrderItem(
    val productId: String,
    val quantity: Int,
    val priceAtOrder: BigDecimal
)