package org.example.queryservice.controller

import org.example.queryservice.document.EnrichedPayment
import org.example.queryservice.dto.PageResponse
import org.example.queryservice.service.PaymentQueryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/query/payments")
class PaymentQueryController(
    private val paymentQueryService: PaymentQueryService
) {

    @GetMapping("/{paymentId}")
    suspend fun getPayment(
        @PathVariable paymentId: String
    ): ResponseEntity<EnrichedPayment> =
        ResponseEntity.ok(paymentQueryService.getPayment(paymentId))

    @GetMapping("/order/{orderId}")
    suspend fun getPaymentByOrder(
        @PathVariable orderId: String
    ): ResponseEntity<EnrichedPayment> =
        ResponseEntity.ok(paymentQueryService.getPaymentByOrder(orderId))

    @GetMapping("/user/{userId}")
    suspend fun getPaymentsByUser(
        @PathVariable userId: String,
        @RequestParam(defaultValue = "0")  page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<EnrichedPayment>> =
        ResponseEntity.ok(paymentQueryService.getPaymentsByUser(userId, page, size))
}
