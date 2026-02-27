package org.example.deliveryservice.dto

import common.document.delivery.DeliveryStatus
import org.example.deliveryservice.document.DeliveryHistory
import java.time.Instant

data class DeliveryHistoryResponse(
    val historyId: String,
    val deliveryId: String,
    val status: DeliveryStatus,
    val location: String,
    val description: String?,
    val timestamp: Instant
)

fun DeliveryHistory.toResponse() = DeliveryHistoryResponse(
    historyId   = historyId,
    deliveryId  = deliveryId,
    status      = status,
    location    = location,
    description = description,
    timestamp   = timestamp
)
