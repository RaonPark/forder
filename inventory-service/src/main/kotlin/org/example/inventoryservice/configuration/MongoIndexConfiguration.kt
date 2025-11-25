package org.example.inventoryservice.configuration

import jakarta.annotation.PostConstruct
import org.example.inventoryservice.document.Inventory
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index

@Configuration
class MongoIndexConfiguration(
    private val mongoTemplate: MongoTemplate
) {
    @PostConstruct
    fun initIndexes() {
        val indexDefinition = Index()
            .on("productId", Sort.Direction.ASC)
            .on("optionId", Sort.Direction.ASC)
            .on("location", Sort.Direction.ASC)
            .unique()
            .named("inventory_unique_index")

        mongoTemplate.indexOps(Inventory::class.java).createIndex(indexDefinition)
    }
}