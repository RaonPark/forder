package org.example.paymentservice.dto

import common.document.payment.RefundType
import java.math.BigDecimal

data class RefundRequest(
    val refundType: RefundType,
    val refundAmount: BigDecimal,
    val reason: String? = null
)
