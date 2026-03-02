package org.example.deliveryservice.controller

import kotlinx.coroutines.flow.Flow
import org.example.deliveryservice.dto.CreateDeliveryRequest
import org.example.deliveryservice.dto.DeliveryHistoryResponse
import org.example.deliveryservice.dto.DeliveryResponse
import org.example.deliveryservice.dto.UpdateDeliveryStatusRequest
import org.example.deliveryservice.service.DeliveryService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/deliveries")
class DeliveryController(
    private val deliveryService: DeliveryService
) {

    // 배송 생성
    @PostMapping
    suspend fun createDelivery(
        @Valid @RequestBody request: CreateDeliveryRequest
    ): ResponseEntity<DeliveryResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(deliveryService.createDelivery(request))

    // 배송 단건 조회
    @GetMapping("/{deliveryId}")
    suspend fun getDelivery(
        @PathVariable deliveryId: String
    ): ResponseEntity<DeliveryResponse> =
        ResponseEntity.ok(deliveryService.getDelivery(deliveryId))

    // 주문별 배송 조회
    @GetMapping("/order/{orderId}")
    suspend fun getDeliveryByOrderId(
        @PathVariable orderId: String
    ): ResponseEntity<DeliveryResponse> =
        ResponseEntity.ok(deliveryService.getDeliveryByOrderId(orderId))

    // 배송 상태 업데이트 (배송 이벤트 수신 시)
    @PatchMapping("/{deliveryId}/status")
    suspend fun updateStatus(
        @PathVariable deliveryId: String,
        @Valid @RequestBody request: UpdateDeliveryStatusRequest
    ): ResponseEntity<DeliveryResponse> =
        ResponseEntity.ok(deliveryService.updateStatus(deliveryId, request))

    // 배송기사 배정
    @PatchMapping("/{deliveryId}/courier/{courierId}")
    suspend fun assignCourier(
        @PathVariable deliveryId: String,
        @PathVariable courierId: String
    ): ResponseEntity<DeliveryResponse> =
        ResponseEntity.ok(deliveryService.assignCourier(deliveryId, courierId))

    // 배송 이력 조회
    @GetMapping("/{deliveryId}/history")
    fun getHistory(
        @PathVariable deliveryId: String
    ): Flow<DeliveryHistoryResponse> =
        deliveryService.getHistory(deliveryId)
}
