package org.example.orderservice.dto

import java.math.BigDecimal
import java.time.Instant

data class CreateOrderResponse(
    val orderId: String,
    val totalAmount: BigDecimal,
    val createdAt: Instant?
)
