package org.example.deliveryservice.dto

import common.document.delivery.DeliveryStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class UpdateDeliveryStatusRequest(
    @NotNull val status: DeliveryStatus,
    @NotBlank val location: String,
    val description: String? = null
)
