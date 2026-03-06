package org.example.deliveryservice.service

import common.document.delivery.DeliveryAddress
import common.document.delivery.DeliveryStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.example.deliveryservice.document.Delivery
import org.example.deliveryservice.document.DeliveryHistory
import org.example.deliveryservice.dto.CreateDeliveryRequest
import org.example.deliveryservice.dto.DeliveryHistoryResponse
import org.example.deliveryservice.dto.DeliveryResponse
import org.example.deliveryservice.dto.UpdateDeliveryStatusRequest
import org.example.deliveryservice.dto.toResponse
import org.example.deliveryservice.exception.CourierNotFoundException
import org.example.deliveryservice.exception.DeliveryNotFoundException
import org.example.deliveryservice.exception.InvalidDeliveryOperationException
import org.springframework.dao.OptimisticLockingFailureException
import org.example.deliveryservice.repository.CourierRepository
import org.example.deliveryservice.repository.DeliveryHistoryRepository
import org.example.deliveryservice.repository.DeliveryRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class DeliveryService(
    private val deliveryRepository: DeliveryRepository,
    private val deliveryHistoryRepository: DeliveryHistoryRepository,
    private val courierRepository: CourierRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // ──────────────────────────────────────────
    // CRUD
    // ──────────────────────────────────────────

    @Transactional
    suspend fun createDelivery(request: CreateDeliveryRequest): DeliveryResponse {
        val courierId = request.courierId ?: courierRepository.findFirstByIsActiveTrue()?.courierId

        val delivery = Delivery(
            deliveryId  = UUID.randomUUID().toString(),
            orderId     = request.orderId,
            userId      = request.userId,
            status      = DeliveryStatus.PENDING,
            courierId   = courierId,
            destination = request.destination,
            items       = request.items
        )
        return deliveryRepository.save(delivery).toResponse()
    }

    suspend fun getDelivery(deliveryId: String): DeliveryResponse =
        findOrThrow(deliveryId).toResponse()

    suspend fun getDeliveryByOrderId(orderId: String): DeliveryResponse =
        deliveryRepository.findByOrderId(orderId)?.toResponse()
            ?: throw DeliveryNotFoundException("배송을 찾을 수 없습니다 - orderId=$orderId")

    @Transactional
    suspend fun updateStatus(deliveryId: String, request: UpdateDeliveryStatusRequest): DeliveryResponse {
        val delivery = findOrThrow(deliveryId)
        validateTransition(delivery.status, request.status)

        val updated = delivery.copy(
            status      = request.status,
            startedAt   = if (request.status == DeliveryStatus.PICKED_UP) Instant.now() else delivery.startedAt,
            completedAt = if (request.status == DeliveryStatus.DELIVERED) Instant.now() else delivery.completedAt
        )
        val saved = deliveryRepository.save(updated)

        // 상태 이력 기록
        deliveryHistoryRepository.save(
            DeliveryHistory(
                historyId   = UUID.randomUUID().toString(),
                deliveryId  = deliveryId,
                status      = request.status,
                location    = request.location,
                description = request.description
            )
        )

        log.info("[Delivery] 상태 변경 - deliveryId={}, status={}", deliveryId, request.status)
        return saved.toResponse()
    }

    @Transactional
    suspend fun assignCourier(deliveryId: String, courierId: String): DeliveryResponse {
        val delivery = findOrThrow(deliveryId)
        if (delivery.status != DeliveryStatus.PENDING)
            throw InvalidDeliveryOperationException("PENDING 상태의 배송에만 배송기사를 배정할 수 있습니다 - status=${delivery.status}")

        courierRepository.findById(courierId)
            ?: throw CourierNotFoundException("배송기사를 찾을 수 없습니다 - courierId=$courierId")

        val trackingNumber = "TRK-${UUID.randomUUID().toString().take(8).uppercase()}"
        return deliveryRepository.save(
            delivery.copy(courierId = courierId, trackingNumber = trackingNumber)
        ).toResponse()
    }

    fun getHistory(deliveryId: String): Flow<DeliveryHistoryResponse> =
        deliveryHistoryRepository.findAllByDeliveryIdOrderByTimestampAsc(deliveryId)
            .map { it.toResponse() }

    // ──────────────────────────────────────────
    // Saga 전용: 배송 생성 / 취소
    // ──────────────────────────────────────────

    @Transactional
    suspend fun createDeliveryFromSaga(
        sagaId: String,
        orderId: String,
        userId: String,
        receiverName: String,
        receiverPhone: String,
        deliveryAddress: String
    ): String {
        // 1차 방어: 애플리케이션 레벨 중복 체크
        deliveryRepository.findBySagaId(sagaId)?.let {
            log.info("[Saga] 이미 처리된 배송 - sagaId={}, deliveryId={}", sagaId, it.deliveryId)
            return it.deliveryId
        }

        val destination = DeliveryAddress(
            receiverName  = receiverName,
            receiverPhone = receiverPhone,
            zipCode       = "",
            baseAddress   = deliveryAddress,
            detailAddress = ""
        )

        val courierId = courierRepository.findFirstByIsActiveTrue()?.courierId
        val trackingNumber = "TRK-${UUID.randomUUID().toString().take(8).uppercase()}"

        return try {
            val delivery = Delivery(
                deliveryId     = UUID.randomUUID().toString(),
                sagaId         = sagaId,
                orderId        = orderId,
                userId         = userId,
                status         = DeliveryStatus.PENDING,
                courierId      = courierId,
                trackingNumber = trackingNumber,
                destination    = destination
            )
            val saved = deliveryRepository.save(delivery)
            log.info("[Saga] 배송 생성 완료 - sagaId={}, deliveryId={}", sagaId, saved.deliveryId)
            saved.deliveryId
        } catch (e: DuplicateKeyException) {
            // 2차 방어: 분산 환경 동시 처리로 unique index 위반
            log.warn("[Saga] sagaId 중복 감지(동시 처리) - sagaId={}", sagaId)
            deliveryRepository.findBySagaId(sagaId)?.deliveryId ?: throw e
        }
    }

    // 단일 Document 조회 후 단일 Document 갱신이므로 @Transactional 불필요.
    // @Version 낙관적 잠금이 WriteConflict 대신 OptimisticLockingFailureException을 발생시켜
    // 아래 catch 블록의 멱등성 체크가 정상 동작한다.
    suspend fun cancelDeliveryFromSaga(orderId: String) {
        val delivery = deliveryRepository.findByOrderId(orderId) ?: run {
            log.warn("[Saga] 취소 대상 배송 없음 - orderId={}", orderId)
            return
        }

        if (delivery.status == DeliveryStatus.DELIVERED)
            throw InvalidDeliveryOperationException("이미 배달 완료된 배송은 취소할 수 없습니다 - orderId=$orderId")

        if (delivery.status == DeliveryStatus.RETURNED_TO_SENDER) {
            log.info("[Saga] 이미 취소된 배송 - orderId={}", orderId)
            return
        }

        try {
            deliveryRepository.save(delivery.copy(status = DeliveryStatus.RETURNED_TO_SENDER))
            log.info("[Saga] 배송 취소 완료 - orderId={}, deliveryId={}", orderId, delivery.deliveryId)
        } catch (e: OptimisticLockingFailureException) {
            val current = deliveryRepository.findByOrderId(orderId)
            when (current?.status) {
                DeliveryStatus.RETURNED_TO_SENDER -> {
                    log.info("[Saga] 동시 취소 처리 - 이미 취소됨 - orderId={}", orderId)
                    return
                }
                DeliveryStatus.DELIVERED -> {
                    // OLF 발생 시점에 이미 DELIVERED로 전이된 경우 — 비즈니스 예외로 변환
                    throw InvalidDeliveryOperationException("이미 배달 완료된 배송은 취소할 수 없습니다 - orderId=$orderId")
                }
                else -> throw e
            }
        }
    }

    // ──────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────

    private suspend fun findOrThrow(deliveryId: String): Delivery =
        deliveryRepository.findById(deliveryId)
            ?: throw DeliveryNotFoundException("배송을 찾을 수 없습니다 - deliveryId=$deliveryId")

    private fun validateTransition(from: DeliveryStatus, to: DeliveryStatus) {
        val allowed = mapOf(
            DeliveryStatus.PENDING           to setOf(DeliveryStatus.PICKED_UP, DeliveryStatus.RETURNED_TO_SENDER),
            DeliveryStatus.PICKED_UP         to setOf(DeliveryStatus.IN_TRANSIT, DeliveryStatus.RETURNED_TO_SENDER),
            DeliveryStatus.IN_TRANSIT        to setOf(DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.RETURNED_TO_SENDER),
            DeliveryStatus.OUT_FOR_DELIVERY  to setOf(DeliveryStatus.DELIVERED, DeliveryStatus.DELIVERY_FAILED),
            DeliveryStatus.DELIVERY_FAILED   to setOf(DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.RETURNED_TO_SENDER),
        )
        if (to !in (allowed[from] ?: emptySet()))
            throw InvalidDeliveryOperationException("허용되지 않은 상태 전이입니다: $from → $to")
    }
}
