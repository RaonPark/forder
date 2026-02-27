package org.example.deliveryservice.dto

import common.document.delivery.DeliveryAddress
import common.document.delivery.DeliveryItemSnapshot
import common.document.delivery.DeliveryStatus
import org.example.deliveryservice.document.Delivery
import java.time.Instant

data class DeliveryResponse(
    val deliveryId: String,
    val orderId: String,
    val userId: String,
    val status: DeliveryStatus,
    val trackingNumber: String?,
    val courierId: String?,
    val destination: DeliveryAddress,
    val items: List<DeliveryItemSnapshot>,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant?,
    val updatedAt: Instant?
)

fun Delivery.toResponse() = DeliveryResponse(
    deliveryId    = deliveryId,
    orderId       = orderId,
    userId        = userId,
    status        = status,
    trackingNumber = trackingNumber,
    courierId     = courierId,
    destination   = destination,
    items         = items,
    startedAt     = startedAt,
    completedAt   = completedAt,
    createdAt     = createdAt,
    updatedAt     = updatedAt
)
