package org.example.queryservice.document

import common.document.CancellationState
import common.document.OrderStatus
import common.document.PaymentMethod
import common.document.ReturnState
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.elasticsearch.annotations.Document
import java.math.BigDecimal
import java.time.Instant

@Document(indexName = "orders")
data class EnrichedOrders(
    @Id
    val orderId: String,
    val userId: String,
    val status: OrderStatus,

    val items: List<EnrichedItem>,
    val totalAmount: BigDecimal,

    val deliveryAddress: String,
    val receiverName: String,
    val receiverPhone: String,

    val paymentMethod: PaymentMethod,
    var paymentTxId: String? = null,

    var trackingNumber: String? = null,
    var deliveryStatus: DeliveryStatus? = null,
    var estimatedDeliveryDate: Instant? = null,
    var actualDeliveryDate: Instant? = null,

    var cancelledAt: Instant? = null,
    var cancelledReason: String? = null,
    var cancellationStatus: CancellationState? = null,

    var returnedAt: String? = null,
    var returnedReason: String? = null,
    var returnStatus: ReturnState? = null,

    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),

    @Version
    var version: Long? = null
)