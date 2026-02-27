package org.example.deliveryservice.dto

import common.document.delivery.DeliveryAddress
import common.document.delivery.DeliveryItemSnapshot

data class CreateDeliveryRequest(
    val orderId: String,
    val userId: String,
    val destination: DeliveryAddress,
    val items: List<DeliveryItemSnapshot>,
    val courierId: String? = null
)
