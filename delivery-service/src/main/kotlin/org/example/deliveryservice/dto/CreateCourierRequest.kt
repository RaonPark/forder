package org.example.deliveryservice.dto

import jakarta.validation.constraints.NotBlank
import org.example.deliveryservice.document.IntegrationType

data class CreateCourierRequest(
    @NotBlank val name: String,
    val trackingUrlTemplate: String? = null,
    val contactPhone: String? = null,
    val integrationType: IntegrationType = IntegrationType.NONE
)
