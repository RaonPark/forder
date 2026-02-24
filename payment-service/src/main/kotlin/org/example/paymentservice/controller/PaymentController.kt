package org.example.paymentservice.controller

import kotlinx.coroutines.flow.Flow
import org.example.paymentservice.document.PaymentRefund
import org.example.paymentservice.dto.CreatePaymentRequest
import org.example.paymentservice.dto.PaymentResponse
import org.example.paymentservice.dto.RefundRequest
import org.example.paymentservice.dto.RefundResponse
import org.example.paymentservice.service.PaymentService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/payment")
class PaymentController(
    private val paymentService: PaymentService
) {

    // 결제 생성
    @PostMapping
    suspend fun createPayment(
        @RequestBody request: CreatePaymentRequest
    ): ResponseEntity<PaymentResponse> {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.createPayment(request))
    }

    // 결제 단건 조회
    @GetMapping("/{paymentId}")
    suspend fun getPayment(
        @PathVariable paymentId: String
    ): ResponseEntity<PaymentResponse> {
        return ResponseEntity.ok(paymentService.getPayment(paymentId))
    }

    // 주문별 결제 조회
    @GetMapping("/order/{orderId}")
    suspend fun getPaymentByOrderId(
        @PathVariable orderId: String
    ): ResponseEntity<PaymentResponse> {
        return ResponseEntity.ok(paymentService.getPaymentByOrderId(orderId))
    }

    // 환불 요청
    @PostMapping("/{paymentId}/refund")
    suspend fun requestRefund(
        @PathVariable paymentId: String,
        @RequestBody request: RefundRequest
    ): ResponseEntity<RefundResponse> {
        return ResponseEntity.ok(paymentService.requestRefund(paymentId, request))
    }

    // 환불 이력 조회
    @GetMapping("/{paymentId}/refunds")
    fun getRefunds(
        @PathVariable paymentId: String
    ): Flow<PaymentRefund> {
        return paymentService.getRefunds(paymentId)
    }
}
