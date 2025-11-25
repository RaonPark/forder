package org.example.inventoryservice.document

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document
data class Inventory(
    @Id
    val inventoryId: String,

    val productId: String,
    val location: String,
    val optionId: String? = null,

    var currentStock: Int,
    var reservedStock: Int,
    val safetyStock: Int,

    @CreatedDate
    val createdAt: Instant = Instant.now(),
    @LastModifiedDate
    val updatedAt: Instant = Instant.now(),
    @Version
    var version: Long? = null
)
