package org.example.deliveryservice.dto

import common.document.delivery.DeliveryAddress
import common.document.delivery.InspectionResult
import common.document.delivery.ReturnDeliveryStatus
import org.example.deliveryservice.document.ReturnDelivery
import java.time.Instant

data class ReturnDeliveryResponse(
    val returnDeliveryId: String,
    val originalDeliveryId: String,
    val orderId: String,
    val returnRequestId: String,
    val pickupAddress: DeliveryAddress,
    val trackingNumber: String?,
    val courierId: String?,
    val status: ReturnDeliveryStatus,
    val inspectionResult: InspectionResult?,
    val createdAt: Instant,
    val updatedAt: Instant
)

fun ReturnDelivery.toResponse() = ReturnDeliveryResponse(
    returnDeliveryId  = returnDeliveryId,
    originalDeliveryId = originalDeliveryId,
    orderId           = orderId,
    returnRequestId   = returnRequestId,
    pickupAddress     = pickupAddress,
    trackingNumber    = trackingNumber,
    courierId         = courierId,
    status            = status,
    inspectionResult  = inspectionResult,
    createdAt         = createdAt,
    updatedAt         = updatedAt
)
