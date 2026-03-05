package org.example.productservice.config

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.example.productservice.document.Products
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.stereotype.Component

@Component
class MongoIndexConfiguration(
    private val mongoTemplate: ReactiveMongoTemplate
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        runBlocking { createProductIndexes() }
    }

    private suspend fun createProductIndexes() {
        val indexOps = mongoTemplate.indexOps(Products::class.java)

        // sellerId 단순 조회 최적화
        indexOps.createIndex(
            Index().on("sellerId", Sort.Direction.ASC).named("idx_products_sellerId")
        ).awaitSingle()

        // category 필터링 최적화
        indexOps.createIndex(
            Index().on("category", Sort.Direction.ASC).named("idx_products_category")
        ).awaitSingle()

        // status 필터링 최적화
        indexOps.createIndex(
            Index().on("status", Sort.Direction.ASC).named("idx_products_status")
        ).awaitSingle()

        // sellerId + status 복합 인덱스 (판매자별 상태 필터링) — @CompoundIndex로도 문서화
        indexOps.createIndex(
            Index()
                .on("sellerId", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC)
                .named("idx_products_sellerId_status")
        ).awaitSingle()

        log.info("[MongoIndex] Products indexes ensured")
    }
}
