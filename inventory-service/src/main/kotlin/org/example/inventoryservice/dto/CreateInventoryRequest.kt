package org.example.inventoryservice.dto

data class CreateInventoryRequest(
    val productId: String,
    val optionId: String? = null,
    val location: String,
    val initialStock: Int,
    val safetyStock: Int = 0
)
