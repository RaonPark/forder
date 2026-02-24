package org.example.paymentservice.dto

import common.document.payment.ApprovalDetails
import common.document.payment.PaymentMethod
import common.document.payment.PaymentStatus
import common.document.payment.PgProvider
import org.example.paymentservice.document.Payment
import java.math.BigDecimal
import java.time.Instant

data class PaymentResponse(
    val paymentId: String,
    val orderId: String,
    val userId: String,
    val totalAmount: BigDecimal,
    val status: PaymentStatus,
    val method: PaymentMethod,
    val pgProvider: PgProvider,
    val pgTid: String?,
    val approvalDetails: ApprovalDetails?,
    val isPartialCanceled: Boolean,
    val canceledAmount: BigDecimal,
    val canceledAt: Instant?,
    val createdAt: Instant?,
    val updatedAt: Instant?
)

fun Payment.toResponse() = PaymentResponse(
    paymentId       = paymentId,
    orderId         = orderId,
    userId          = userId,
    totalAmount     = totalAmount,
    status          = status,
    method          = method,
    pgProvider      = pgProvider,
    pgTid           = pgTid,
    approvalDetails = approvalDetails,
    isPartialCanceled = isPartialCanceled,
    canceledAmount  = canceledAmount,
    canceledAt      = canceledAt,
    createdAt       = createdAt,
    updatedAt       = updatedAt
)
