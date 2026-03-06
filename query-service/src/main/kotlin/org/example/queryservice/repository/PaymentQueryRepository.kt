package org.example.queryservice.repository

import org.example.queryservice.document.EnrichedPayment
import org.springframework.data.domain.Pageable
import org.springframework.data.elasticsearch.repository.ReactiveElasticsearchRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface PaymentQueryRepository : ReactiveElasticsearchRepository<EnrichedPayment, String> {

    fun findByOrderId(orderId: String): Mono<EnrichedPayment>

    fun findByUserId(userId: String, pageable: Pageable): Flux<EnrichedPayment>

    fun countByUserId(userId: String): Mono<Long>
}
