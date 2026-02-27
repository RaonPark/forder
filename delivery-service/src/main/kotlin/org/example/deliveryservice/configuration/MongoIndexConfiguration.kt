package org.example.deliveryservice.configuration

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

    override fun run(args: ApplicationArguments) = runBlocking {
        // deliveries: orderId 조회용 인덱스
        reactiveMongoTemplate.indexOps("deliveries")
            .createIndex(Index().on("orderId", Sort.Direction.ASC))
            .awaitSingle()

        // deliveries: sagaId sparse unique (null 허용, Saga 중복 커맨드 차단)
        reactiveMongoTemplate.indexOps("deliveries")
            .createIndex(
                Index().on("sagaId", Sort.Direction.ASC).unique().sparse()
            )
            .awaitSingle()

        // return_deliveries: orderId 조회용 인덱스
        reactiveMongoTemplate.indexOps("return_deliveries")
            .createIndex(Index().on("orderId", Sort.Direction.ASC))
            .awaitSingle()

        // delivery_history: deliveryId 조회용 인덱스
        reactiveMongoTemplate.indexOps("delivery_history")
            .createIndex(Index().on("deliveryId", Sort.Direction.ASC))
            .awaitSingle()
    }
}
