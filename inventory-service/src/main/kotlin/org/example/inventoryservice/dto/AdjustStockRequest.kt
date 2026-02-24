package org.example.inventoryservice.dto

import common.document.inventory.InventoryReferenceSource

data class AdjustStockRequest(
    val quantityDelta: Int,           // 양수 = 입고, 음수 = 출고
    val referenceId: String,          // 연관 문서 ID (주문ID, 입고전표ID 등)
    val referenceSource: InventoryReferenceSource,
    val userId: String? = null
)
