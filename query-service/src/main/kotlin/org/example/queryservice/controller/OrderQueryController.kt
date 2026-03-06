package org.example.queryservice.controller

import common.document.order.OrderStatus
import org.example.queryservice.document.EnrichedOrders
import org.example.queryservice.dto.PageResponse
import org.example.queryservice.service.OrderQueryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/query/orders")
class OrderQueryController(
    private val orderQueryService: OrderQueryService
) {

    @GetMapping("/{orderId}")
    suspend fun getOrder(
        @PathVariable orderId: String
    ): ResponseEntity<EnrichedOrders> =
        ResponseEntity.ok(orderQueryService.getOrder(orderId))

    @GetMapping("/user/{userId}")
    suspend fun getOrdersByUser(
        @PathVariable userId: String,
        @RequestParam(required = false) status: OrderStatus?,
        @RequestParam(defaultValue = "0")  page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<EnrichedOrders>> =
        ResponseEntity.ok(orderQueryService.getOrdersByUser(userId, status, page, size))
}
