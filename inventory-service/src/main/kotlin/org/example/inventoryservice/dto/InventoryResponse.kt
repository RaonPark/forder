package org.example.inventoryservice.dto

import org.example.inventoryservice.document.Inventory
import java.time.Instant

data class InventoryResponse(
    val inventoryId: String,
    val productId: String,
    val optionId: String?,
    val location: String,
    val currentStock: Int,
    val reservedStock: Int,
    val availableStock: Int,   // currentStock - reservedStock
    val safetyStock: Int,
    val updatedAt: Instant?
)

fun Inventory.toResponse() = InventoryResponse(
    inventoryId   = inventoryId,
    productId     = productId,
    optionId      = optionId,
    location      = location,
    currentStock  = currentStock,
    reservedStock = reservedStock,
    availableStock = currentStock - reservedStock,
    safetyStock   = safetyStock,
    updatedAt     = updatedAt
)
