package org.example.deliveryservice

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun kafkaContainer(): KafkaContainer {
        return KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"))
    }

    /**
     * ReactiveMongoTransactionManager requires a replica set.
     * We start MongoDB with --replSet rs0 and initialize it post-start so
     * @Transactional works in integration tests without a real 3-node cluster.
     * directConnection=true bypasses replica-set topology discovery on a single-node set.
     */
    @Bean
    @ServiceConnection
    fun mongoDbContainer(): MongoDBContainer {
        return object : MongoDBContainer(DockerImageName.parse("mongo:latest")) {
            override fun start() {
                withCommand("--replSet", "rs0", "--bind_ip_all")
                super.start()
                execInContainer(
                    "mongosh", "--eval",
                    "rs.initiate({_id:'rs0', members:[{_id:0, host:'localhost:27017'}]})"
                )
            }

            // super returns "mongodb://host:port" (no DB name).
            // Append /forder so Spring Data doesn't get an empty database name.
            // directConnection=true skips replica-set topology discovery on a single-node set.
            override fun getConnectionString(): String =
                super.getConnectionString() + "/forder?directConnection=true"
        }
    }

}
