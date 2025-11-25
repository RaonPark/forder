package org.example.orderservice.document

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant

import common.document.CancellationState
import common.document.OrderStatus
import common.document.ReturnState

@Document(collection = "orders")
data class Orders(
    @Id
    val orderId: String,
    val userId: String,
    var status: OrderStatus,

    val items: List<OrderItem>,
    val totalAmount: BigDecimal,

    val deliveryAddress: String,

    val receiverName: String,
    val receiverPhone: String,

    var paymentTxId: String? = null,

    var deliveryId: String? = null,

    var cancelledAt: Instant? = null,
    var cancelledReason: String? = null,
    var cancellationStatus: CancellationState? = null,

    var returnedAt: Instant? = null,
    var returnReason: String? = null,
    var returnStatus: ReturnState? = null,

    @CreatedDate
    val createdAt: Instant? = null,
    @LastModifiedDate
    var updatedAt: Instant? = null,

    @Version
    var version: Long? = null
)