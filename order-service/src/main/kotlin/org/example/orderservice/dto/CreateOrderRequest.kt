package org.example.orderservice.dto

import java.math.BigDecimal

data class CreateOrderRequest(
    val userId: String,
    val items: List<OrderItemRequest>,
    val deliveryAddress: String,
    val receiverName: String,
    val receiverPhone: String
)

data class OrderItemRequest(
    val productId: String,
    val quantity: Int,
    val priceAtOrder: BigDecimal
)
