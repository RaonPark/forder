package org.example.deliveryservice.dto

import org.example.deliveryservice.document.IntegrationType

data class CreateCourierRequest(
    val name: String,
    val trackingUrlTemplate: String? = null,
    val contactPhone: String? = null,
    val integrationType: IntegrationType = IntegrationType.NONE
)
