package org.example.queryservice.controller

import common.document.product.ProductStatus
import org.example.queryservice.document.EnrichedProducts
import org.example.queryservice.dto.PageResponse
import org.example.queryservice.service.ProductQueryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/query/products")
class ProductQueryController(
    private val productQueryService: ProductQueryService
) {

    @GetMapping("/{productId}")
    suspend fun getProduct(
        @PathVariable productId: String
    ): ResponseEntity<EnrichedProducts> =
        ResponseEntity.ok(productQueryService.getProduct(productId))

    @GetMapping("/search")
    suspend fun searchProducts(
        @RequestParam(required = false)    keyword: String?,
        @RequestParam(required = false)    category: String?,
        @RequestParam(required = false)    status: ProductStatus?,
        @RequestParam(required = false)    priceRange: String?,
        @RequestParam(defaultValue = "0")  page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<EnrichedProducts>> =
        ResponseEntity.ok(productQueryService.searchProducts(keyword, category, status, priceRange, page, size))

    @GetMapping("/seller/{sellerName}")
    suspend fun getProductsBySeller(
        @PathVariable sellerName: String,
        @RequestParam(defaultValue = "0")  page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<EnrichedProducts>> =
        ResponseEntity.ok(productQueryService.getProductsBySeller(sellerName, page, size))
}
