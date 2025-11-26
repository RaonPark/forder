package org.example.paymentservice.document

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant

@Document(collection = "payments")
data class Payment (
    @Id
    val paymentId: String,

    val orderId: String,
    val userId: String,

    val totalAmount: BigDecimal,
    val status: PaymentStatus,
    val method: PaymentMethod,

    var pgTid: String? = null,
    var pgProvider: PgProvider,
    var approvalDetails: ApprovalDetails? = null,

    var isPartialCanceled: Boolean = false,
    var canceledAmount: BigDecimal = BigDecimal.ZERO,
    var canceledAt: Instant? = null,

    @CreatedDate
    val createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @Version
    var version: Long? = null
)