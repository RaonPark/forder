package org.example.queryservice.document

import common.document.delivery.DeliveryStatus
import common.document.delivery.EnrichedDeliveryItem
import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import java.time.Instant

@Document(indexName = "deliveries-view")
data class EnrichedDelivery (
    @Id
    val deliveryId: String,

    @Field(type = FieldType.Keyword)
    val orderId: String,

    @Field(type = FieldType.Keyword)
    val userId: String,

    @Field(type = FieldType.Keyword)
    val trackingNumber: String?,

    @Field(type = FieldType.Text, analyzer = "korean_analyzer", searchAnalyzer="korean_analyzer")
    val recipientName: String,

    val items: List<EnrichedDeliveryItem>,

    @Field(type = FieldType.Keyword)
    val status: DeliveryStatus,

    @Field(type = FieldType.Keyword)
    val courierId: String?,

    @Field(type = FieldType.Keyword)
    val courierName: String?,

    @Field(type = FieldType.Text)
    val currentLocation: String?,

    @Field(type = FieldType.Keyword)
    val fullTrackingUrl: String?,

    @Field(type = FieldType.Text)
    val lastDescription: String?,

    val startedAt: Instant?,
    val estimatedArrival: Instant?,
    val deliveredAt: Instant?
)