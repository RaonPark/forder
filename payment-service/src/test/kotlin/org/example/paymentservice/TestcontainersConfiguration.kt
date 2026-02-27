package org.example.paymentservice

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.DependsOn
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    companion object {
        val testcontainersNetwork: Network = Network.newNetwork()
    }

    @Bean
    @ServiceConnection
    fun kafkaContainer() = ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:8.0.3"))
        .withNetwork(testcontainersNetwork)
        .withNetworkAliases("kafka")
        // Schema Registry가 Docker 내부 네트워크에서 Kafka로 접근할 리스너를 추가한다.
        // 외부(테스트 JVM)는 containerProperties의 firstMappedPort로 주입하고,
        // 컨테이너 간 통신은 kafka:19092 로 고정된다.
        .withListener("kafka:19092")

    @Bean
    @ServiceConnection
    fun mongoContainer() = MongoDBContainer(DockerImageName.parse("mongo:latest"))
        .withNetwork(testcontainersNetwork)

    @Bean
    @DependsOn("kafkaContainer")
    fun schemaRegistry(kafka: ConfluentKafkaContainer) =
        GenericContainer<Nothing>("confluentinc/cp-schema-registry:8.0.3")
            .apply {
                withExposedPorts(8081)
                withNetwork(testcontainersNetwork)
                withNetworkAliases("schema-registry")
                withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
                withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
                withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "kafka:19092")
                waitingFor(Wait.forHttp("/subjects").forStatusCode(200))
                dependsOn(kafka)
            }

    // @ServiceConnection이 ConfluentKafkaContainer를 지원하지 않으므로
    // spring.kafka.bootstrap-servers도 명시적으로 주입한다.
    @Bean
    fun containerProperties(
        kafka: ConfluentKafkaContainer,
        schemaRegistry: GenericContainer<*>
    ): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
        registry.add("spring.kafka.bootstrap-servers") { "localhost:${kafka.firstMappedPort}" }
        registry.add("spring.kafka.properties.schema.registry.url") {
            "http://localhost:${schemaRegistry.firstMappedPort}"
        }
    }
}
