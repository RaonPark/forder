package org.example.queryservice.service

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.example.queryservice.document.EnrichedDelivery
import org.example.queryservice.dto.PageResponse
import org.example.queryservice.exception.QueryResourceNotFoundException
import org.example.queryservice.repository.DeliveryQueryRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class DeliveryQueryService(
    private val deliveryQueryRepository: DeliveryQueryRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getDelivery(deliveryId: String): EnrichedDelivery =
        deliveryQueryRepository.findById(deliveryId).awaitSingleOrNull()
            ?: throw QueryResourceNotFoundException("Delivery", deliveryId)

    suspend fun getDeliveryByOrder(orderId: String): EnrichedDelivery =
        deliveryQueryRepository.findByOrderId(orderId).awaitSingleOrNull()
            ?: throw QueryResourceNotFoundException("Delivery for order", orderId)

    suspend fun getDeliveriesByUser(
        userId: String,
        page: Int,
        size: Int
    ): PageResponse<EnrichedDelivery> {
        val pageable = PageRequest.of(page, size)
        val (content, total) = Mono.zip(
            deliveryQueryRepository.findByUserId(userId, pageable).collectList(),
            deliveryQueryRepository.countByUserId(userId)
        ).awaitSingle().let { it.t1 to it.t2 }

        log.info("[Query] 사용자 배송 조회 - userId={}, total={}", userId, total)
        return PageResponse.of(content, page, size, total)
    }
}
