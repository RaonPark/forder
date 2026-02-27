package org.example.deliveryservice.dto

import org.example.deliveryservice.document.Courier
import org.example.deliveryservice.document.IntegrationType

data class CourierResponse(
    val courierId: String,
    val name: String,
    val trackingUrlTemplate: String?,
    val contactPhone: String?,
    val integrationType: IntegrationType,
    val isActive: Boolean
)

fun Courier.toResponse() = CourierResponse(
    courierId            = courierId,
    name                 = name,
    trackingUrlTemplate  = trackingUrlTemplate,
    contactPhone         = contactPhone,
    integrationType      = integrationType,
    isActive             = isActive
)
