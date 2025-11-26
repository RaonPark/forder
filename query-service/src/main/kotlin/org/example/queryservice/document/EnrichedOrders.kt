package org.example.queryservice.document

import common.document.*
import common.document.delivery.DeliveryStatus
import common.document.order.CancellationState
import common.document.order.OrderStatus
import common.document.order.ReturnState
import common.document.payment.PaymentMethod
import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import java.math.BigDecimal
import java.time.Instant

@Document(indexName = "orders-view")
data class EnrichedOrders(
    @Id
    val orderId: String,
    @Field(type = FieldType.Keyword)
    val userId: String,
    @Field(type = FieldType.Keyword)
    val status: OrderStatus,

    val items: List<EnrichedItem>,
    val totalAmount: BigDecimal,

    val deliveryAddress: String,
    val receiverName: String,
    val receiverPhone: String,

    @Field(type = FieldType.Keyword)
    val paymentMethod: PaymentMethod,
    var paymentTxId: String? = null,

    @Field(type = FieldType.Keyword)
    var trackingNumber: String? = null,
    @Field(type = FieldType.Keyword)
    var deliveryStatus: DeliveryStatus? = null,
    var estimatedDeliveryDate: Instant? = null,
    var actualDeliveryDate: Instant? = null,

    var cancelledAt: Instant? = null,
    @Field(type = FieldType.Text)
    var cancelledReason: String? = null,
    @Field(type = FieldType.Keyword)
    var cancellationStatus: CancellationState? = null,

    var returnedAt: String? = null,
    @Field(type = FieldType.Text)
    var returnedReason: String? = null,
    @Field(type = FieldType.Keyword)
    var returnStatus: ReturnState? = null,

    val createdAt: Instant,
    val updatedAt: Instant,
)