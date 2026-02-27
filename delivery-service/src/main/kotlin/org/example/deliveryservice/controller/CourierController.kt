package org.example.deliveryservice.controller

import kotlinx.coroutines.flow.Flow
import org.example.deliveryservice.document.Courier
import org.example.deliveryservice.dto.CourierResponse
import org.example.deliveryservice.dto.CreateCourierRequest
import org.example.deliveryservice.service.CourierService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/couriers")
class CourierController(
    private val courierService: CourierService
) {

    // 배송기사 등록
    @PostMapping
    suspend fun createCourier(
        @RequestBody request: CreateCourierRequest
    ): ResponseEntity<CourierResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(courierService.createCourier(request))

    // 배송기사 단건 조회
    @GetMapping("/{courierId}")
    suspend fun getCourier(
        @PathVariable courierId: String
    ): ResponseEntity<CourierResponse> =
        ResponseEntity.ok(courierService.getCourier(courierId))

    // 활성 배송기사 목록 조회
    @GetMapping
    fun listActiveCouriers(): Flow<Courier> =
        courierService.listActiveCouriers()

    // 배송기사 비활성화
    @DeleteMapping("/{courierId}")
    suspend fun deactivateCourier(
        @PathVariable courierId: String
    ): ResponseEntity<CourierResponse> =
        ResponseEntity.ok(courierService.deactivateCourier(courierId))
}
