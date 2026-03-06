package org.example.queryservice.repository

import org.example.queryservice.document.EnrichedProducts
import org.springframework.data.domain.Pageable
import org.springframework.data.elasticsearch.repository.ReactiveElasticsearchRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface ProductQueryRepository : ReactiveElasticsearchRepository<EnrichedProducts, String> {

    fun findBySellerName(sellerName: String, pageable: Pageable): Flux<EnrichedProducts>

    fun countBySellerName(sellerName: String): Mono<Long>
}
