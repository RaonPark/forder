package org.example.paymentservice.dto

import common.document.payment.PaymentMethod
import common.document.payment.PaymentStatus
import common.document.payment.PgProvider
import java.math.BigDecimal
import java.time.Instant

data class CreatePaymentResponse(
    val paymentId: String,
    val orderId: String,
    val status: PaymentStatus,
    val totalAmount: BigDecimal,
    val method: PaymentMethod,
    val pgProvider: PgProvider,
    val createdAt: Instant
)
