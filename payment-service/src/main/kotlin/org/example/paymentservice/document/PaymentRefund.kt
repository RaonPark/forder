package org.example.paymentservice.document

import common.document.payment.RefundStatus
import common.document.payment.RefundType
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant

@Document(collection = "payment_refunds")
data class PaymentRefund(
    @Id
    val refundId: String,

    val paymentId: String,
    val orderId: String,
    val refundType: RefundType,

    val refundAmount: BigDecimal,
    val feeAmount: BigDecimal = BigDecimal.ZERO,

    val reason: String? = null,
    val pgRefundTid: String? = null,

    val status: RefundStatus,

    val createdAt: Instant = Instant.now()
)
