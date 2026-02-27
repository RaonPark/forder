package org.example.deliveryservice.repository

import kotlinx.coroutines.flow.Flow
import org.example.deliveryservice.document.DeliveryHistory
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.kotlin.CoroutineSortingRepository

interface DeliveryHistoryRepository :
    CoroutineCrudRepository<DeliveryHistory, String>,
    CoroutineSortingRepository<DeliveryHistory, String> {

    fun findAllByDeliveryIdOrderByTimestampAsc(deliveryId: String): Flow<DeliveryHistory>
}
