package org.example.inventoryservice.repository

import kotlinx.coroutines.flow.Flow
import org.example.inventoryservice.document.InventoryHistory
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface InventoryHistoryRepository : CoroutineCrudRepository<InventoryHistory, String> {

    fun findAllByInventoryIdOrderByTimestampDesc(inventoryId: String): Flow<InventoryHistory>

    fun findAllByProductIdOrderByTimestampDesc(productId: String): Flow<InventoryHistory>
}
