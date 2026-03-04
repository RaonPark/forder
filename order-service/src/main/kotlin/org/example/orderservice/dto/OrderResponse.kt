package org.example.orderservice.dto

import common.document.order.CancellationState
import common.document.order.OrderStatus
import common.document.order.ReturnState
import org.example.orderservice.document.Orders
import java.math.BigDecimal
import java.time.Instant

data class OrderResponse(
    val orderId: String,
    val userId: String,
    val status: OrderStatus,
    val items: List<OrderItemResponse>,
    val totalAmount: BigDecimal,
    val deliveryAddress: String,
    val receiverName: String,
    val receiverPhone: String,
    val paymentTxId: String?,
    val deliveryId: String?,
    val cancellationState: CancellationState?,
    val returnState: ReturnState?,
    val createdAt: Instant?,
    val updatedAt: Instant?
) {
    companion object {
        fun from(order: Orders) = OrderResponse(
            orderId = order.orderId,
            userId = order.userId,
            status = order.status,
            items = order.items.map { OrderItemResponse(it.productId, it.quantity, it.priceAtOrder) },
            totalAmount = order.totalAmount,
            deliveryAddress = order.deliveryAddress,
            receiverName = order.receiverName,
            receiverPhone = order.receiverPhone,
            paymentTxId = order.paymentTxId,
            deliveryId = order.deliveryId,
            cancellationState = order.cancellationStatus,
            returnState = order.returnStatus,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt
        )
    }
}

data class OrderItemResponse(
    val productId: String,
    val quantity: Int,
    val priceAtOrder: BigDecimal
)
