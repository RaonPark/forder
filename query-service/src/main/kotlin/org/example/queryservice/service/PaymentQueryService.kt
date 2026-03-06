package org.example.queryservice.service

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.example.queryservice.document.EnrichedPayment
import org.example.queryservice.dto.PageResponse
import org.example.queryservice.exception.QueryResourceNotFoundException
import org.example.queryservice.repository.PaymentQueryRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class PaymentQueryService(
    private val paymentQueryRepository: PaymentQueryRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getPayment(paymentId: String): EnrichedPayment =
        paymentQueryRepository.findById(paymentId).awaitSingleOrNull()
            ?: throw QueryResourceNotFoundException("Payment", paymentId)

    suspend fun getPaymentByOrder(orderId: String): EnrichedPayment =
        paymentQueryRepository.findByOrderId(orderId).awaitSingleOrNull()
            ?: throw QueryResourceNotFoundException("Payment for order", orderId)

    suspend fun getPaymentsByUser(
        userId: String,
        page: Int,
        size: Int
    ): PageResponse<EnrichedPayment> {
        val pageable = PageRequest.of(page, size)
        val (content, total) = Mono.zip(
            paymentQueryRepository.findByUserId(userId, pageable).collectList(),
            paymentQueryRepository.countByUserId(userId)
        ).awaitSingle().let { it.t1 to it.t2 }

        log.info("[Query] 사용자 결제 조회 - userId={}, total={}", userId, total)
        return PageResponse.of(content, page, size, total)
    }
}
