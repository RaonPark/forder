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

    val sagaId: String? = null,     // Saga 커맨드 중복 처리 방지용 멱등성 키

    val orderId: String,
    val userId: String,

    var status: DeliveryStatus,

    var trackingNumber: String? = null,
    var courierId: String? = null,

    val destination: DeliveryAddress,

    val items: List<DeliveryItemSnapshot> = emptyList(),

    var startedAt: Instant? = null,
    var completedAt: Instant? = null,

    @CreatedDate
    val createdAt: Instant? = null,
    @LastModifiedDate
    var updatedAt: Instant? = null,
    @Version
    var version: Long? = null
)
