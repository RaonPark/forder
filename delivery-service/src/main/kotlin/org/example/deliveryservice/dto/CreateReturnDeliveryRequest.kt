package org.example.deliveryservice.dto

import common.document.delivery.DeliveryAddress
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank

data class CreateReturnDeliveryRequest(
    @NotBlank val originalDeliveryId: String,
    @NotBlank val orderId: String,
    @NotBlank val returnRequestId: String,
    @Valid val pickupAddress: DeliveryAddress,
    val courierId: String? = null
)
