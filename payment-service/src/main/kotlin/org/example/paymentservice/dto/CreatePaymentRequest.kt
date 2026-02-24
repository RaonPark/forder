package org.example.paymentservice.dto

import common.document.payment.PaymentMethod
import common.document.payment.PgProvider
import java.math.BigDecimal

data class CreatePaymentRequest(
    val orderId: String,
    val userId: String,
    val totalAmount: BigDecimal,
    val method: PaymentMethod,
    val pgProvider: PgProvider
)
