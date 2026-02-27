package org.example.deliveryservice.service

import kotlinx.coroutines.flow.Flow
import org.example.deliveryservice.document.Courier
import org.example.deliveryservice.dto.CourierResponse
import org.example.deliveryservice.dto.CreateCourierRequest
import org.example.deliveryservice.dto.toResponse
import org.example.deliveryservice.exception.CourierNotFoundException
import org.example.deliveryservice.repository.CourierRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CourierService(
    private val courierRepository: CourierRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun createCourier(request: CreateCourierRequest): CourierResponse {
        val courier = Courier(
            courierId           = UUID.randomUUID().toString(),
            name                = request.name,
            trackingUrlTemplate = request.trackingUrlTemplate,
            contactPhone        = request.contactPhone,
            integrationType     = request.integrationType
        )
        return courierRepository.save(courier).toResponse()
    }

    suspend fun getCourier(courierId: String): CourierResponse =
        courierRepository.findById(courierId)?.toResponse()
            ?: throw CourierNotFoundException("배송기사를 찾을 수 없습니다 - courierId=$courierId")

    fun listActiveCouriers(): Flow<Courier> =
        courierRepository.findAllByIsActiveTrue()

    suspend fun deactivateCourier(courierId: String): CourierResponse {
        val courier = courierRepository.findById(courierId)
            ?: throw CourierNotFoundException("배송기사를 찾을 수 없습니다 - courierId=$courierId")
        val updated = courierRepository.save(courier.copy(isActive = false))
        log.info("[Courier] 비활성화 - courierId={}", courierId)
        return updated.toResponse()
    }
}
