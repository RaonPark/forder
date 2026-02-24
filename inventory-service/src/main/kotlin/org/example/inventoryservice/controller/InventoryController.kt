package org.example.inventoryservice.controller

import kotlinx.coroutines.flow.Flow
import org.example.inventoryservice.document.InventoryHistory
import org.example.inventoryservice.dto.AdjustStockRequest
import org.example.inventoryservice.dto.CreateInventoryRequest
import org.example.inventoryservice.dto.InventoryResponse
import org.example.inventoryservice.service.InventoryService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/inventory")
class InventoryController(
    private val inventoryService: InventoryService
) {

    // 재고 등록
    @PostMapping
    suspend fun createInventory(
        @RequestBody request: CreateInventoryRequest
    ): ResponseEntity<InventoryResponse> {
        val response = inventoryService.createInventory(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    // 재고 단건 조회
    @GetMapping("/{inventoryId}")
    suspend fun getInventory(
        @PathVariable inventoryId: String
    ): ResponseEntity<InventoryResponse> {
        return ResponseEntity.ok(inventoryService.getInventory(inventoryId))
    }

    // 상품별 재고 목록 조회
    @GetMapping
    fun getInventories(
        @RequestParam productId: String
    ): Flow<InventoryResponse> {
        return inventoryService.getInventoriesByProductId(productId)
    }

    // 재고 수량 조정 (입고 / 수동 출고)
    @PatchMapping("/{inventoryId}/stock")
    suspend fun adjustStock(
        @PathVariable inventoryId: String,
        @RequestBody request: AdjustStockRequest
    ): ResponseEntity<InventoryResponse> {
        return ResponseEntity.ok(inventoryService.adjustStock(inventoryId, request))
    }

    // 재고 삭제
    @DeleteMapping("/{inventoryId}")
    suspend fun deleteInventory(
        @PathVariable inventoryId: String
    ): ResponseEntity<Void> {
        inventoryService.deleteInventory(inventoryId)
        return ResponseEntity.noContent().build()
    }

    // 재고 변동 이력 조회
    @GetMapping("/{inventoryId}/history")
    fun getInventoryHistory(
        @PathVariable inventoryId: String
    ): Flow<InventoryHistory> {
        return inventoryService.getInventoryHistory(inventoryId)
    }
}
