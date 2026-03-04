package org.example.orderservice.repository

import common.document.order.OrderStatus
import kotlinx.coroutines.flow.Flow
import org.example.orderservice.document.Orders
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.kotlin.CoroutineSortingRepository
import java.time.Instant

interface OrderRepository: CoroutineSortingRepository<Orders, String>, CoroutineCrudRepository<Orders, String> {

    /**
     * Saga 타임아웃 감지용 쿼리.
     * 지정된 상태 목록 중 하나이고 updatedAt 이 threshold 이전인 주문을 반환한다.
     * SagaTimeoutService 에서 상태별 세부 임계값으로 재필터링하여 사용.
     */
    fun findByStatusInAndUpdatedAtBefore(
        statuses: Collection<OrderStatus>,
        threshold: Instant
    ): Flow<Orders>
}