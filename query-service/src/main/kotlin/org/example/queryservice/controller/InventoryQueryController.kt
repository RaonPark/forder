package org.example.queryservice.controller

import common.document.product.StockStatus
import org.example.queryservice.document.EnrichedInventory
import org.example.queryservice.dto.PageResponse
import org.example.queryservice.service.InventoryQueryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/query/inventory")
class InventoryQueryController(
    private val inventoryQueryService: InventoryQueryService
) {

    @GetMapping("/{inventoryId}")
    suspend fun getInventory(
        @PathVariable inventoryId: String
    ): ResponseEntity<EnrichedInventory> =
        ResponseEntity.ok(inventoryQueryService.getInventory(inventoryId))

    @GetMapping("/product/{productId}")
    suspend fun getInventoryByProduct(
        @PathVariable productId: String
    ): ResponseEntity<List<EnrichedInventory>> =
        ResponseEntity.ok(inventoryQueryService.getInventoryByProduct(productId))

    @GetMapping("/search")
    suspend fun searchInventory(
        @RequestParam(required = false)    keyword: String?,
        @RequestParam(required = false)    stockStatus: StockStatus?,
        @RequestParam(required = false)    category: String?,
        @RequestParam(defaultValue = "0")  page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<EnrichedInventory>> =
        ResponseEntity.ok(inventoryQueryService.searchInventory(keyword, stockStatus, category, page, size))
}
