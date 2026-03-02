package org.example.deliveryservice.service

import common.document.delivery.InspectionResult
import common.document.delivery.ReturnDeliveryStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.example.deliveryservice.document.ReturnDelivery
import org.example.deliveryservice.dto.CompleteInspectionRequest
import org.example.deliveryservice.dto.CreateReturnDeliveryRequest
import org.example.deliveryservice.dto.ReturnDeliveryResponse
import org.example.deliveryservice.dto.toResponse
import org.example.deliveryservice.exception.InvalidDeliveryOperationException
import org.example.deliveryservice.exception.ReturnDeliveryNotFoundException
import org.example.deliveryservice.repository.CourierRepository
import org.example.deliveryservice.repository.ReturnDeliveryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ReturnDeliveryService(
    private val returnDeliveryRepository: ReturnDeliveryRepository,
    private val courierRepository: CourierRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun createReturnDelivery(request: CreateReturnDeliveryRequest): ReturnDeliveryResponse {
        val courierId = request.courierId ?: courierRepository.findFirstByIsActiveTrue()?.courierId

        val returnDelivery = ReturnDelivery(
            returnDeliveryId   = UUID.randomUUID().toString(),
            originalDeliveryId = request.originalDeliveryId,
            orderId            = request.orderId,
            returnRequestId    = request.returnRequestId,
            pickupAddress      = request.pickupAddress,
            courierId          = courierId,
            status             = ReturnDeliveryStatus.PICKUP_REQUESTED
        )
        return returnDeliveryRepository.save(returnDelivery).toResponse()
    }

    suspend fun getReturnDelivery(returnDeliveryId: String): ReturnDeliveryResponse =
        findOrThrow(returnDeliveryId).toResponse()

    fun getReturnDeliveriesByOrderId(orderId: String): Flow<ReturnDeliveryResponse> =
        returnDeliveryRepository.findAllByOrderId(orderId).map { it.toResponse() }

    @Transactional
    suspend fun updateStatus(returnDeliveryId: String, status: ReturnDeliveryStatus): ReturnDeliveryResponse {
        val returnDelivery = findOrThrow(returnDeliveryId)
        validateTransition(returnDelivery.status, status)
        val updated = returnDeliveryRepository.save(returnDelivery.copy(status = status))
        log.info("[ReturnDelivery] 상태 변경 - returnDeliveryId={}, status={}", returnDeliveryId, status)
        return updated.toResponse()
    }

    @Transactional
    suspend fun completeInspection(returnDeliveryId: String, request: CompleteInspectionRequest): ReturnDeliveryResponse {
        val returnDelivery = findOrThrow(returnDeliveryId)

        if (returnDelivery.status != ReturnDeliveryStatus.ARRIVED_AT_WAREHOUSE)
            throw InvalidDeliveryOperationException(
                "창고 도착 상태에서만 검수를 완료할 수 있습니다 - status=${returnDelivery.status}"
            )

        val inspectionResult = InspectionResult(
            isResellable      = request.isResellable,
            faultType         = request.faultType,
            inspectorComment  = request.inspectorComment
        )
        val finalStatus = if (request.isResellable)
            ReturnDeliveryStatus.INSPECTION_COMPLETED
        else
            ReturnDeliveryStatus.INSPECTION_FAILED

        val updated = returnDeliveryRepository.save(
            returnDelivery.copy(status = finalStatus, inspectionResult = inspectionResult)
        )
        log.info("[ReturnDelivery] 검수 완료 - returnDeliveryId={}, resellable={}", returnDeliveryId, request.isResellable)
        return updated.toResponse()
    }

    private suspend fun findOrThrow(returnDeliveryId: String): ReturnDelivery =
        returnDeliveryRepository.findById(returnDeliveryId)
            ?: throw ReturnDeliveryNotFoundException("반품 배송을 찾을 수 없습니다 - returnDeliveryId=$returnDeliveryId")

    private fun validateTransition(from: ReturnDeliveryStatus, to: ReturnDeliveryStatus) {
        val allowed = mapOf(
            ReturnDeliveryStatus.PICKUP_REQUESTED    to setOf(ReturnDeliveryStatus.PICKUP_COMPLETED),
            ReturnDeliveryStatus.PICKUP_COMPLETED    to setOf(ReturnDeliveryStatus.RETURNING),
            ReturnDeliveryStatus.RETURNING           to setOf(ReturnDeliveryStatus.ARRIVED_AT_WAREHOUSE),
            ReturnDeliveryStatus.ARRIVED_AT_WAREHOUSE to setOf(
                ReturnDeliveryStatus.INSPECTION_COMPLETED,
                ReturnDeliveryStatus.INSPECTION_FAILED
            )
        )
        if (to !in (allowed[from] ?: emptySet()))
            throw InvalidDeliveryOperationException("허용되지 않은 반품 상태 전이입니다: $from → $to")
    }
}
