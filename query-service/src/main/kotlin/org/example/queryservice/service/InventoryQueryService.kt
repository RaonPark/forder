package org.example.queryservice.service

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import common.document.product.StockStatus
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.example.queryservice.document.EnrichedInventory
import org.example.queryservice.dto.PageResponse
import org.example.queryservice.exception.QueryResourceNotFoundException
import org.example.queryservice.repository.InventoryQueryRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class InventoryQueryService(
    private val inventoryQueryRepository: InventoryQueryRepository,
    private val elasticsearchOperations: ReactiveElasticsearchOperations
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getInventory(inventoryId: String): EnrichedInventory =
        inventoryQueryRepository.findById(inventoryId).awaitSingleOrNull()
            ?: throw QueryResourceNotFoundException("Inventory", inventoryId)

    suspend fun getInventoryByProduct(productId: String): List<EnrichedInventory> =
        inventoryQueryRepository.findByProductId(productId).collectList().awaitSingle()

    suspend fun searchInventory(
        keyword: String?,
        stockStatus: StockStatus?,
        category: String?,
        page: Int,
        size: Int
    ): PageResponse<EnrichedInventory> {
        val pageable  = PageRequest.of(page, size)
        val query      = buildSearchQuery(keyword, stockStatus, category, pageable)
        val countQuery = buildSearchQuery(keyword, stockStatus, category, null)

        val dataFlux  = elasticsearchOperations.search(query, EnrichedInventory::class.java).map { it.content }.collectList()
        val countMono = elasticsearchOperations.count(countQuery, EnrichedInventory::class.java)

        val (content, total) = Mono.zip(dataFlux, countMono).awaitSingle().let { it.t1 to it.t2 }
        log.info("[Query] 재고 검색 - keyword={}, stockStatus={}, total={}", keyword, stockStatus, total)
        return PageResponse.of(content, page, size, total)
    }

    // ──────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────

    private fun buildSearchQuery(
        keyword: String?,
        stockStatus: StockStatus?,
        category: String?,
        pageable: Pageable?
    ): NativeQuery {
        val query = Query.of { q ->
            q.bool { b ->
                if (keyword     != null) b.must   { m -> m.match { it.field("productName").query(keyword) } }
                if (stockStatus != null) b.filter { f -> f.term  { it.field("stockStatus").value(stockStatus.name) } }
                if (category    != null) b.filter { f -> f.term  { it.field("category").value(category) } }
                b
            }
        }
        val builder = NativeQuery.builder().withQuery(query)
        if (pageable != null) builder.withPageable(pageable)
        return builder.build()
    }
}
