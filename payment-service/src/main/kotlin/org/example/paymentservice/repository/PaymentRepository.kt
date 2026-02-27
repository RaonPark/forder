package org.example.paymentservice.repository

import org.example.paymentservice.document.Payment
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.kotlin.CoroutineSortingRepository

interface PaymentRepository : CoroutineCrudRepository<Payment, String>, CoroutineSortingRepository<Payment, String> {

    suspend fun findByOrderId(orderId: String): Payment?

    suspend fun findBySagaId(sagaId: String): Payment?
}
