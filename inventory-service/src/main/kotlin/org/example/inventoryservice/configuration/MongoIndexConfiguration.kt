package org.example.inventoryservice.configuration

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.example.inventoryservice.document.Inventory
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.Index

@Configuration
class MongoIndexConfiguration(
    private val reactiveMongoTemplate: ReactiveMongoTemplate
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    // ApplicationRunner: 애플리케이션이 완전히 시작된 후 실행
    // @PostConstruct + .block() 대신 사용 - reactive 컨텍스트 밖에서 안전하게 초기화
    // auto-index-creation=false(기본값) 유지 - 프로덕션에서 매 시작 시 인덱스 생성 방지
    override fun run(args: ApplicationArguments) = runBlocking {
        log.info("[Index] Ensuring indexes for Inventory collection...")
        reactiveMongoTemplate
            .indexOps(Inventory::class.java)
            .createIndex(
                Index()
                    .on("productId", Sort.Direction.ASC)
                    .on("optionId", Sort.Direction.ASC)
                    .on("location", Sort.Direction.ASC)
                    .unique()
                    .named("inventory_unique_index")
            )
            .awaitSingle()
        log.info("[Index] Inventory indexes ready")
    }
}