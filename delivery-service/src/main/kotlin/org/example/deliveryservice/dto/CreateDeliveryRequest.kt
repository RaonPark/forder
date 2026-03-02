package org.example.deliveryservice.dto

import common.document.delivery.DeliveryAddress
import common.document.delivery.DeliveryItemSnapshot
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

data class CreateDeliveryRequest(
    @NotBlank val orderId: String,
    @NotBlank val userId: String,
    @Valid val destination: DeliveryAddress,
    @NotEmpty val items: List<DeliveryItemSnapshot>,
    val courierId: String? = null
)
