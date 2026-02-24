package org.example.inventoryservice.repository

import kotlinx.coroutines.flow.Flow
import org.example.inventoryservice.document.Inventory
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.kotlin.CoroutineSortingRepository

interface InventoryRepository :
    CoroutineCrudRepository<Inventory, String>,
    CoroutineSortingRepository<Inventory, String> {

    fun findAllByProductId(productId: String): Flow<Inventory>

    fun findAllByLocation(location: String): Flow<Inventory>

    // optionId가 null일 수 있으므로 @Query로 명시
    @Query("{ 'productId': ?0, 'optionId': ?1, 'location': ?2 }")
    suspend fun findByProductIdAndOptionIdAndLocation(
        productId: String,
        optionId: String?,
        location: String
    ): Inventory?

    // Saga용: 위치 무관하게 productId + optionId로 조회
    @Query("{ 'productId': ?0, 'optionId': ?1 }")
    fun findAllByProductIdAndOptionId(productId: String, optionId: String?): Flow<Inventory>
}
