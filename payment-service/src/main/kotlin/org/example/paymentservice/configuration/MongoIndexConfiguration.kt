package org.example.paymentservice.configuration

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.stereotype.Component

@Component
class MongoIndexConfiguration(
    private val reactiveMongoTemplate: ReactiveMongoTemplate
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        // sagaId sparse unique index
        // - null인 문서는 인덱싱 제외 → REST API 결제(sagaId=null)는 여러 건이어도 충돌 없음
        // - sagaId가 있는 문서끼리만 유니크 보장 → Kafka 중복 커맨드 DB 레벨에서 차단
        val sagaIdIndex = Index()
            .on("sagaId", Sort.Direction.ASC)
            .unique()
            .sparse()

        runBlocking {
            reactiveMongoTemplate.indexOps("payments")
                .createIndex(sagaIdIndex)
                .awaitSingle()

            reactiveMongoTemplate.indexOps("payment_refunds")
                .createIndex(sagaIdIndex)
                .awaitSingle()
        }
    }
}
