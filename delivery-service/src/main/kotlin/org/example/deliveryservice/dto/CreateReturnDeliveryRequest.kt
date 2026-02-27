package org.example.deliveryservice.dto

import common.document.delivery.DeliveryAddress

data class CreateReturnDeliveryRequest(
    val originalDeliveryId: String,
    val orderId: String,
    val returnRequestId: String,
    val pickupAddress: DeliveryAddress,
    val courierId: String? = null
)
