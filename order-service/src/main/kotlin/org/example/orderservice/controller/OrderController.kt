package org.example.orderservice.controller

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController(value = "/v1/orders")
class OrderController {
    @PostMapping(value = ["/"])
    suspend fun createOrder() {

    }
}