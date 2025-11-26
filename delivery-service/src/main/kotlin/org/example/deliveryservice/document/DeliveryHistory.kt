package org.example.deliveryservice.document

import common.document.delivery.DeliveryStatus
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "delivery_history")
data class DeliveryHistory(
    @Id
    val historyId: String,

    val deliveryId: String,

    val status: DeliveryStatus,
    val location: String,
    val description: String? = null,

    val timestamp: Instant = Instant.now()
)
