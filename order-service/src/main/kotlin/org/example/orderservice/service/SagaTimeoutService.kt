package org.example.orderservice.service

import common.document.order.OrderStatus
import kotlinx.coroutines.flow.collect
import org.example.orderservice.dto.TimeoutOutcome
import org.example.orderservice.dto.TimeoutProcessResult
import org.example.orderservice.repository.OrderRepository
import org.example.orderservice.saga.SagaTimeoutHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class SagaTimeoutService(
    private val orderRepository: OrderRepository,
    private val timeoutHandler: SagaTimeoutHandler
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // Saga 상태별 타임아웃 임계값
        // 생성 Saga 는 정상이라면 수 초 내 완료 → 30분은 충분히 보수적
        const val CREATION_TIMEOUT_MINUTES     = 30L
        const val CANCELLATION_TIMEOUT_MINUTES = 60L
        const val RETURN_TIMEOUT_MINUTES       = 120L

        private val TIMEOUT_STATUSES = listOf(
            OrderStatus.PENDING,
            OrderStatus.CANCELLING,
            OrderStatus.RETURN_REQUESTED,
            OrderStatus.RETURN_IN_PROGRESS
        )
    }

    /**
     * 멈춘 Saga 를 탐지하고 보상 처리를 위임한다.
     * Airflow DAG 의 POST /internal/saga/timeout/process 에서 호출.
     *
     * 멱등성: 이미 terminal 상태인 주문은 쿼리에서 제외되므로 중복 호출 안전.
     */
    suspend fun processStuckOrders(): TimeoutProcessResult {
        val now = Instant.now()

        // 가장 짧은 임계값(30분)으로 쿼리하여 전체 후보를 한 번에 조회
        val minThreshold = now.minusSeconds(CREATION_TIMEOUT_MINUTES * 60)

        val cancelled          = mutableListOf<String>()
        val cancellationFailed = mutableListOf<String>()
        val returnFailed       = mutableListOf<String>()
        val skipped            = mutableListOf<String>()

        orderRepository.findByStatusInAndUpdatedAtBefore(TIMEOUT_STATUSES, minThreshold)
            .collect { order ->
                // 상태별 세부 임계값으로 재필터링
                val threshold    = thresholdFor(order.status, now)
                val lastActivity = order.updatedAt ?: order.createdAt ?: return@collect
                if (!lastActivity.isBefore(threshold)) return@collect

                // 개별 주문 처리 실패가 전체를 중단시키지 않도록 격리
                runCatching { timeoutHandler.handle(order) }
                    .onSuccess { outcome ->
                        when (outcome) {
                            TimeoutOutcome.CANCELLED           -> cancelled          += order.orderId
                            TimeoutOutcome.CANCELLATION_FAILED -> cancellationFailed += order.orderId
                            TimeoutOutcome.RETURN_FAILED       -> returnFailed       += order.orderId
                            TimeoutOutcome.SKIPPED             -> skipped            += order.orderId
                        }
                    }
                    .onFailure { e ->
                        log.error("[Timeout] Failed to process orderId={}", order.orderId, e)
                        skipped += order.orderId
                    }
            }

        val processedCount = cancelled.size + cancellationFailed.size + returnFailed.size
        log.info(
            "[Timeout] Scan complete - processed={}, cancelled={}, cancellationFailed={}, returnFailed={}, skipped={}",
            processedCount, cancelled.size, cancellationFailed.size, returnFailed.size, skipped.size
        )

        return TimeoutProcessResult(
            processedCount         = processedCount,
            cancelledOrders        = cancelled,
            cancellationFailedOrders = cancellationFailed,
            returnFailedOrders     = returnFailed,
            skippedOrders          = skipped
        )
    }

    private fun thresholdFor(status: OrderStatus, now: Instant): Instant = when (status) {
        OrderStatus.PENDING    -> now.minusSeconds(CREATION_TIMEOUT_MINUTES * 60)
        OrderStatus.CANCELLING -> now.minusSeconds(CANCELLATION_TIMEOUT_MINUTES * 60)
        else                   -> now.minusSeconds(RETURN_TIMEOUT_MINUTES * 60)
    }
}
