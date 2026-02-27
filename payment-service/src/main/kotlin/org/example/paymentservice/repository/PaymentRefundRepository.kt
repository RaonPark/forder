package org.example.paymentservice.repository

import kotlinx.coroutines.flow.Flow
import org.example.paymentservice.document.PaymentRefund
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface PaymentRefundRepository : CoroutineCrudRepository<PaymentRefund, String> {

    fun findAllByPaymentId(paymentId: String): Flow<PaymentRefund>

    suspend fun existsBySagaId(sagaId: String): Boolean
}
