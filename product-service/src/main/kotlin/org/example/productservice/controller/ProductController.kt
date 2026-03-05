package org.example.productservice.controller

import common.document.product.ProductStatus
import kotlinx.coroutines.flow.toList
import org.example.productservice.dto.CreateProductRequest
import org.example.productservice.dto.ProductResponse
import org.example.productservice.dto.UpdateProductRequest
import org.example.productservice.dto.UpdateProductStatusRequest
import org.example.productservice.service.ProductService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/products")
class ProductController(
    private val productService: ProductService
) {

    @PostMapping
    suspend fun createProduct(
        @RequestBody request: CreateProductRequest
    ): ResponseEntity<ProductResponse> {
        val response = productService.createProduct(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/{productId}")
    suspend fun getProduct(
        @PathVariable productId: String
    ): ResponseEntity<ProductResponse> =
        ResponseEntity.ok(productService.getProduct(productId))

    @PutMapping("/{productId}")
    suspend fun updateProduct(
        @PathVariable productId: String,
        @RequestBody request: UpdateProductRequest
    ): ResponseEntity<ProductResponse> =
        ResponseEntity.ok(productService.updateProduct(productId, request))

    @PatchMapping("/{productId}/status")
    suspend fun changeStatus(
        @PathVariable productId: String,
        @RequestBody request: UpdateProductStatusRequest
    ): ResponseEntity<ProductResponse> =
        ResponseEntity.ok(productService.changeStatus(productId, request))

    // sellerId 필수, status 선택 (없으면 전체 반환)
    @GetMapping("/seller/{sellerId}")
    suspend fun getProductsBySeller(
        @PathVariable sellerId: String,
        @RequestParam(required = false) status: ProductStatus?
    ): ResponseEntity<List<ProductResponse>> {
        val products = if (status != null) {
            productService.getProductsBySellerAndStatus(sellerId, status).toList()
        } else {
            productService.getProductsBySeller(sellerId).toList()
        }
        return ResponseEntity.ok(products)
    }
}
