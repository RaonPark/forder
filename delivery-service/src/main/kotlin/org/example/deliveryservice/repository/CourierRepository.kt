package org.example.deliveryservice.repository

import kotlinx.coroutines.flow.Flow
import org.example.deliveryservice.document.Courier
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.kotlin.CoroutineSortingRepository

interface CourierRepository :
    CoroutineCrudRepository<Courier, String>,
    CoroutineSortingRepository<Courier, String> {

    fun findAllByIsActiveTrue(): Flow<Courier>
    suspend fun findFirstByIsActiveTrue(): Courier?
}
