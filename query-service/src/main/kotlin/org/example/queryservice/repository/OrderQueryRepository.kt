package org.example.queryservice.repository

import common.document.order.OrderStatus
import org.example.queryservice.document.EnrichedOrders
import org.springframework.data.domain.Pageable
import org.springframework.data.elasticsearch.repository.ReactiveElasticsearchRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface OrderQueryRepository : ReactiveElasticsearchRepository<EnrichedOrders, String> {

    fun findByUserId(userId: String, pageable: Pageable): Flux<EnrichedOrders>

    fun findByUserIdAndStatus(userId: String, status: OrderStatus, pageable: Pageable): Flux<EnrichedOrders>

    fun countByUserId(userId: String): Mono<Long>

    fun countByUserIdAndStatus(userId: String, status: OrderStatus): Mono<Long>
}
