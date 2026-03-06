package org.example.queryservice.repository

import org.example.queryservice.document.EnrichedDelivery
import org.springframework.data.domain.Pageable
import org.springframework.data.elasticsearch.repository.ReactiveElasticsearchRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface DeliveryQueryRepository : ReactiveElasticsearchRepository<EnrichedDelivery, String> {

    fun findByOrderId(orderId: String): Mono<EnrichedDelivery>

    fun findByUserId(userId: String, pageable: Pageable): Flux<EnrichedDelivery>

    fun countByUserId(userId: String): Mono<Long>
}
