package org.example.deliveryservice.document

import common.document.delivery.DeliveryAddress
import common.document.delivery.DeliveryItemSnapshot
import common.document.delivery.DeliveryStatus
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "deliveries")
data class Delivery(
    @Id
    val deliveryId: String,

    val orderId: String,
    val userId: String,

    var status: DeliveryStatus,

    var trackingNumber: String? = null,
    var courierId: String? = null,

    val destination: DeliveryAddress,

    val items: List<DeliveryItemSnapshot>,

    var startedAt: Instant? = null,
    var completedAt: Instant? = null,

    @CreatedDate
    val createdAt: Instant = Instant.now(),
    @LastModifiedDate
    val updatedAt: Instant = Instant.now(),
    @Version
    var version: Long? = null
)
