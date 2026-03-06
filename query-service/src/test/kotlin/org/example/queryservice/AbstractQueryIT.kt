package org.example.queryservice

import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.elasticsearch.ElasticsearchContainer
import org.testcontainers.utility.DockerImageName

/**
 * 모든 Query-service IT의 기반 클래스.
 *
 * companion object에 @JvmStatic + @DynamicPropertySource를 두면
 * Spring이 ApplicationContext 생성 전에 spring.elasticsearch.uris를 주입하므로
 * Repository 빈이 올바른 URL로 연결된다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
abstract class AbstractQueryIT {

    companion object {

        @JvmField
        val elasticsearch: ElasticsearchContainer =
            ElasticsearchContainer(
                DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.3.1")
            )
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.elasticsearch.uris") { "http://${elasticsearch.httpHostAddress}" }
        }
    }
}
