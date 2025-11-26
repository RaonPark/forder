package org.example.deliveryservice.document

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "couriers")
data class Courier(
    @Id
    val courierId: String,

    val name: String,

    val trackingUrlTemplate: String? = null,

    val contactPhone: String? = null,

    val integrationType: IntegrationType,

    val isActive: Boolean = true
)