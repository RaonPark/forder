package org.example.paymentservice.dto

import common.document.payment.RefundStatus
import common.document.payment.RefundType
import java.math.BigDecimal
import java.time.Instant

data class RefundResponse(
    val refundId: String,
    val paymentId: String,
    val orderId: String,
    val refundType: RefundType,
    val refundAmount: BigDecimal,
    val feeAmount: BigDecimal,
    val reason: String?,
    val status: RefundStatus,
    val createdAt: Instant
)
