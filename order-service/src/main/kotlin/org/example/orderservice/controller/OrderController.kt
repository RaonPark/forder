package org.example.orderservice.controller

import org.example.orderservice.dto.CancelOrderRequest
import org.example.orderservice.dto.CreateOrderRequest
import org.example.orderservice.dto.CreateOrderResponse
import org.example.orderservice.dto.OrderResponse
import org.example.orderservice.dto.ReturnOrderRequest
import org.example.orderservice.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/orders")
class OrderController(
    private val orderService: OrderService
) {

    @PostMapping
    suspend fun createOrder(@RequestBody request: CreateOrderRequest): ResponseEntity<CreateOrderResponse> {
        val response = orderService.createOrder(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/{orderId}")
    suspend fun getOrder(@PathVariable orderId: String): ResponseEntity<OrderResponse> {
        val response = orderService.getOrder(orderId)
        return ResponseEntity.ok(response)
    }

    @PatchMapping("/{orderId}/cancel")
    suspend fun cancelOrder(
        @PathVariable orderId: String,
        @RequestBody request: CancelOrderRequest
    ): ResponseEntity<OrderResponse> {
        val response = orderService.cancelOrder(orderId, request)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/{orderId}/returns")
    suspend fun requestReturn(
        @PathVariable orderId: String,
        @RequestBody request: ReturnOrderRequest
    ): ResponseEntity<OrderResponse> {
        val response = orderService.requestReturn(orderId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }
}
