package org.example.queryservice.controller

import org.example.queryservice.document.EnrichedDelivery
import org.example.queryservice.dto.PageResponse
import org.example.queryservice.service.DeliveryQueryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/query/deliveries")
class DeliveryQueryController(
    private val deliveryQueryService: DeliveryQueryService
) {

    @GetMapping("/{deliveryId}")
    suspend fun getDelivery(
        @PathVariable deliveryId: String
    ): ResponseEntity<EnrichedDelivery> =
        ResponseEntity.ok(deliveryQueryService.getDelivery(deliveryId))

    @GetMapping("/order/{orderId}")
    suspend fun getDeliveryByOrder(
        @PathVariable orderId: String
    ): ResponseEntity<EnrichedDelivery> =
        ResponseEntity.ok(deliveryQueryService.getDeliveryByOrder(orderId))

    @GetMapping("/user/{userId}")
    suspend fun getDeliveriesByUser(
        @PathVariable userId: String,
        @RequestParam(defaultValue = "0")  page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<EnrichedDelivery>> =
        ResponseEntity.ok(deliveryQueryService.getDeliveriesByUser(userId, page, size))
}
