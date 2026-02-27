package org.example.deliveryservice.repository

import org.example.deliveryservice.document.Delivery
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.kotlin.CoroutineSortingRepository

interface DeliveryRepository :
    CoroutineCrudRepository<Delivery, String>,
    CoroutineSortingRepository<Delivery, String> {

    suspend fun findByOrderId(orderId: String): Delivery?
    suspend fun findBySagaId(sagaId: String): Delivery?
    suspend fun existsBySagaId(sagaId: String): Boolean
}
