package org.example.deliveryservice.document

import common.document.delivery.DeliveryAddress
import common.document.delivery.InspectionResult
import common.document.delivery.ReturnDeliveryStatus
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "return_deliveries")
data class ReturnDelivery(
    @Id
    val returnDeliveryId: String,

    val originalDeliveryId: String,
    val orderId: String,
    val returnRequestId: String,

    val pickupAddress: DeliveryAddress,
    var trackingNumber: String? = null,
    var courierId: String? = null,

    var status: ReturnDeliveryStatus,

    var inspectionResult: InspectionResult? = null,

    @CreatedDate
    val createdAt: Instant = Instant.now(),
    @LastModifiedDate
    val updatedAt: Instant = Instant.now(),
    @Version
    val version: Long? = null
)
