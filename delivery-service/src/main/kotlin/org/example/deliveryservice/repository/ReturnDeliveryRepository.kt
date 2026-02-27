package org.example.deliveryservice.repository

import kotlinx.coroutines.flow.Flow
import org.example.deliveryservice.document.ReturnDelivery
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.kotlin.CoroutineSortingRepository

interface ReturnDeliveryRepository :
    CoroutineCrudRepository<ReturnDelivery, String>,
    CoroutineSortingRepository<ReturnDelivery, String> {

    fun findAllByOrderId(orderId: String): Flow<ReturnDelivery>
    suspend fun findByReturnRequestId(returnRequestId: String): ReturnDelivery?
}
