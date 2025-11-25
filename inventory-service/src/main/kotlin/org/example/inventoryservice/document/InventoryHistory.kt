package org.example.inventoryservice.document

import common.document.InventoryHistoryChangeType
import common.document.InventoryReferenceSource
import common.document.StockType
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "inventory_history")
data class InventoryHistory(
    @Id
    val inventoryHistoryId: String,

    val inventoryId: String,
    val productId: String,
    val optionId: String? = null,
    val location: String,

    val changeType: InventoryHistoryChangeType,
    val quantityDelta: Int,
    val stockType: StockType,

    val beforeStock: Int,
    val afterStock: Int,

    val referenceId: String,
    val referenceSource: InventoryReferenceSource,
    val userId: String? = null,

    val timestamp: Instant = Instant.now()
)
