package org.example.orderservice.config

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.example.orderservice.document.Orders
import org.example.orderservice.document.OutboxEvent
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
        runBlocking {
            createOrderIndexes()
            createOutboxIndexes()
        }
    }

    private suspend fun createOrderIndexes() {
        val indexOps = mongoTemplate.indexOps(Orders::class.java)

        // userId 조회 최적화
        indexOps.ensureIndex(
            Index().on("userId", Sort.Direction.ASC).named("idx_orders_userId")
        ).awaitSingle()

        // status 필터링 최적화
        indexOps.ensureIndex(
            Index().on("status", Sort.Direction.ASC).named("idx_orders_status")
        ).awaitSingle()

        // userId + status 복합 인덱스 (사용자별 상태 조회)
        indexOps.ensureIndex(
            Index()
                .on("userId", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC)
                .named("idx_orders_userId_status")
        ).awaitSingle()

        log.info("[MongoIndex] Orders indexes ensured")
    }

    private suspend fun createOutboxIndexes() {
        val indexOps = mongoTemplate.indexOps(OutboxEvent::class.java)

        // aggregateId(sagaId)로 Outbox 이벤트 조회
        indexOps.ensureIndex(
            Index().on("aggregateId", Sort.Direction.ASC).named("idx_outbox_aggregateId")
        ).awaitSingle()

        // createdAt 기반 CDC 처리 순서 보장
        indexOps.ensureIndex(
            Index().on("createdAt", Sort.Direction.ASC).named("idx_outbox_createdAt")
        ).awaitSingle()

        log.info("[MongoIndex] Outbox indexes ensured")
    }
}
