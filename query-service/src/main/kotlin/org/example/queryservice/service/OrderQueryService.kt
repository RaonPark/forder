package org.example.queryservice.service

import common.document.order.OrderStatus
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.example.queryservice.document.EnrichedOrders
import org.example.queryservice.dto.PageResponse
import org.example.queryservice.exception.QueryResourceNotFoundException
import org.example.queryservice.repository.OrderQueryRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class OrderQueryService(
    private val orderQueryRepository: OrderQueryRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getOrder(orderId: String): EnrichedOrders =
        orderQueryRepository.findById(orderId).awaitSingleOrNull()
            ?: throw QueryResourceNotFoundException("Order", orderId)

    suspend fun getOrdersByUser(
        userId: String,
        status: OrderStatus?,
        page: Int,
        size: Int
    ): PageResponse<EnrichedOrders> {
        val pageable = PageRequest.of(page, size)
        val (dataFlux, countMono) = if (status != null) {
            orderQueryRepository.findByUserIdAndStatus(userId, status, pageable) to
                    orderQueryRepository.countByUserIdAndStatus(userId, status)
        } else {
            orderQueryRepository.findByUserId(userId, pageable) to
                    orderQueryRepository.countByUserId(userId)
        }

        val (content, total) = Mono.zip(dataFlux.collectList(), countMono).awaitSingle().let { it.t1 to it.t2 }
        log.info("[Query] 사용자 주문 조회 - userId={}, status={}, total={}", userId, status, total)
        return PageResponse.of(content, page, size, total)
    }
}
