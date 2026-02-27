package org.example.deliveryservice.dto

import common.document.delivery.DeliveryStatus

data class UpdateDeliveryStatusRequest(
    val status: DeliveryStatus,
    val location: String,
    val description: String? = null
)
