package org.example.queryservice.repository

import org.example.queryservice.document.EnrichedInventory
import org.springframework.data.elasticsearch.repository.ReactiveElasticsearchRepository
import reactor.core.publisher.Flux

interface InventoryQueryRepository : ReactiveElasticsearchRepository<EnrichedInventory, String> {

    fun findByProductId(productId: String): Flux<EnrichedInventory>
}
