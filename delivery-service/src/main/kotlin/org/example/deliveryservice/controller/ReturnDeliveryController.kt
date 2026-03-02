package org.example.deliveryservice.controller

import kotlinx.coroutines.flow.Flow
import org.example.deliveryservice.dto.CompleteInspectionRequest
import org.example.deliveryservice.dto.CreateReturnDeliveryRequest
import org.example.deliveryservice.dto.ReturnDeliveryResponse
import org.example.deliveryservice.service.ReturnDeliveryService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/return-deliveries")
class ReturnDeliveryController(
    private val returnDeliveryService: ReturnDeliveryService
) {

    // 반품 배송 생성
    @PostMapping
    suspend fun createReturnDelivery(
        @Valid @RequestBody request: CreateReturnDeliveryRequest
    ): ResponseEntity<ReturnDeliveryResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(returnDeliveryService.createReturnDelivery(request))

    // 반품 배송 단건 조회
    @GetMapping("/{returnDeliveryId}")
    suspend fun getReturnDelivery(
        @PathVariable returnDeliveryId: String
    ): ResponseEntity<ReturnDeliveryResponse> =
        ResponseEntity.ok(returnDeliveryService.getReturnDelivery(returnDeliveryId))

    // 주문별 반품 배송 목록 조회
    @GetMapping("/order/{orderId}")
    fun getReturnDeliveriesByOrderId(
        @PathVariable orderId: String
    ): Flow<ReturnDeliveryResponse> =
        returnDeliveryService.getReturnDeliveriesByOrderId(orderId)

    // 검수 완료 처리
    @PostMapping("/{returnDeliveryId}/inspection")
    suspend fun completeInspection(
        @PathVariable returnDeliveryId: String,
        @Valid @RequestBody request: CompleteInspectionRequest
    ): ResponseEntity<ReturnDeliveryResponse> =
        ResponseEntity.ok(returnDeliveryService.completeInspection(returnDeliveryId, request))
}
