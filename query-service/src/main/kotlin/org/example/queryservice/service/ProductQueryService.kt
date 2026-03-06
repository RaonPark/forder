package org.example.queryservice.service

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import common.document.product.ProductStatus
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.example.queryservice.document.EnrichedProducts
import org.example.queryservice.dto.PageResponse
import org.example.queryservice.exception.QueryResourceNotFoundException
import org.example.queryservice.repository.ProductQueryRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class ProductQueryService(
    private val productQueryRepository: ProductQueryRepository,
    private val elasticsearchOperations: ReactiveElasticsearchOperations
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getProduct(productId: String): EnrichedProducts =
        productQueryRepository.findById(productId).awaitSingleOrNull()
            ?: throw QueryResourceNotFoundException("Product", productId)

    suspend fun searchProducts(
        keyword: String?,
        category: String?,
        status: ProductStatus?,
        priceRange: String?,
        page: Int,
        size: Int
    ): PageResponse<EnrichedProducts> {
        val pageable  = PageRequest.of(page, size)
        val query      = buildSearchQuery(keyword, category, status, priceRange, pageable)
        val countQuery = buildSearchQuery(keyword, category, status, priceRange, null)

        val dataFlux  = elasticsearchOperations.search(query, EnrichedProducts::class.java).map { it.content }.collectList()
        val countMono = elasticsearchOperations.count(countQuery, EnrichedProducts::class.java)

        val (content, total) = Mono.zip(dataFlux, countMono).awaitSingle().let { it.t1 to it.t2 }
        log.info("[Query] 상품 검색 - keyword={}, category={}, total={}", keyword, category, total)
        return PageResponse.of(content, page, size, total)
    }

    suspend fun getProductsBySeller(
        sellerName: String,
        page: Int,
        size: Int
    ): PageResponse<EnrichedProducts> {
        val pageable = PageRequest.of(page, size)
        val (content, total) = Mono.zip(
            productQueryRepository.findBySellerName(sellerName, pageable).collectList(),
            productQueryRepository.countBySellerName(sellerName)
        ).awaitSingle().let { it.t1 to it.t2 }

        log.info("[Query] 셀러별 상품 조회 - sellerName={}, total={}", sellerName, total)
        return PageResponse.of(content, page, size, total)
    }

    // ──────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────

    private fun buildSearchQuery(
        keyword: String?,
        category: String?,
        status: ProductStatus?,
        priceRange: String?,
        pageable: Pageable?
    ): NativeQuery {
        val query = Query.of { q ->
            q.bool { b ->
                if (keyword    != null) b.must   { m -> m.match { it.field("fullTextSearch").query(keyword) } }
                if (category   != null) b.filter { f -> f.term  { it.field("category").value(category) } }
                if (status     != null) b.filter { f -> f.term  { it.field("status").value(status.name) } }
                if (priceRange != null) b.filter { f -> f.term  { it.field("priceRangeBucket").value(priceRange) } }
                b
            }
        }
        val builder = NativeQuery.builder().withQuery(query)
        if (pageable != null) builder.withPageable(pageable)
        return builder.build()
    }
}
