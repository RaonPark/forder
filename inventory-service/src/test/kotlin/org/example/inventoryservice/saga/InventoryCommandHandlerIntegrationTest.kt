package org.example.inventoryservice.saga

import com.google.protobuf.GeneratedMessage
import common.saga.command.InventoryCommand
import common.saga.command.InventoryCommandType
import common.saga.command.inventoryCommand
import common.saga.command.inventoryCommandItem
import common.saga.reply.InventoryReply
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializerConfig
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.example.inventoryservice.TestcontainersConfiguration
import org.example.inventoryservice.service.InventoryService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ──────────────────────────────────────────────────────────────
// [설계 의도]
//
// @ServiceConnection + @Import(TestcontainersConfiguration) 조합은
// spring.kafka.bootstrap-servers 오버라이드가 보장되지 않는다.
// → @Testcontainers + @Container + @DynamicPropertySource 로 컨테이너를
//   컴패니언 오브젝트에서 직접 관리하고 속성을 명시적으로 주입한다.
//
// Schema Registry 컨테이너(TestcontainersConfiguration)를 사용한다.
// → 앱 producer/consumer와 테스트 consumer 모두 같은 Schema Registry를 바라보므로
//   스키마 ID 불일치 없이 Protobuf 직렬화/역직렬화가 올바르게 동작한다.
//
// InventoryService 는 MockK mock 으로 대체한다.
// → 서비스 로직은 단위 테스트(InventoryServiceTest)에서 검증 완료
// → 이 테스트는 Kafka 직렬화, @KafkaListener 라우팅, Reply 발행만 검증
// → replica set 없이 standalone MongoDB 로도 동작 가능
// ──────────────────────────────────────────────────────────────

@TestConfiguration
class InventoryServiceMockConfig {
    val mock: InventoryService = mockk(relaxed = true)

    @Bean
    @Primary
    fun inventoryService(): InventoryService = mock
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(InventoryServiceMockConfig::class, TestcontainersConfiguration::class)
class InventoryCommandHandlerIntegrationTest {
    // 앱 컨텍스트에서 주입된 KafkaTemplate (KafkaProtobufSerializer + mock://test 설정)
    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, GeneratedMessage>

    @Autowired
    private lateinit var mockConfig: InventoryServiceMockConfig

    @Value("\${spring.kafka.bootstrap-servers}")
    private lateinit var bootstrapServers: String

    @Value("\${spring.kafka.properties.schema.registry.url}")
    private lateinit var schemaRegistryUrl: String

    // inventory-reply 전용 테스트 소비자
    private lateinit var replyConsumer: KafkaConsumer<String, GeneratedMessage>

    @BeforeEach
    fun setUp() {
        clearMocks(mockConfig.mock)
        replyConsumer = KafkaConsumer(replyConsumerProps())
        replyConsumer.subscribe(listOf("inventory-reply"))
    }

    @AfterEach
    fun tearDown() {
        replyConsumer.close()
    }

    // ──────────────────────────────────────────
    // RESERVE 커맨드
    // ──────────────────────────────────────────

    @Nested
    inner class Reserve {

        @Test
        fun `재고 예약 성공 시 success=true reply 발행`() {
            val sagaId = uniqueSagaId()
            coEvery { mockConfig.mock.reserveStock(sagaId, any()) } returns true

            send(reserveCommand(sagaId), sagaId)

            val reply = awaitReply(sagaId)
            assertNotNull(reply)
            assertTrue(reply.success)
            assertEquals(sagaId, reply.sagaId)
            assertTrue(reply.failureReason.isEmpty())
        }

        @Test
        fun `재고 부족 시 success=false + failureReason 포함 reply 발행`() {
            val sagaId = uniqueSagaId()
            coEvery { mockConfig.mock.reserveStock(sagaId, any()) } returns false

            send(reserveCommand(sagaId), sagaId)

            val reply = awaitReply(sagaId)
            assertNotNull(reply)
            assertFalse(reply.success)
            assertEquals(sagaId, reply.sagaId)
            assertTrue(reply.failureReason.isNotBlank())
        }

        @Test
        fun `service 예외 발생 시 success=false + 예외 메시지 포함 reply 발행`() {
            val sagaId = uniqueSagaId()
            coEvery { mockConfig.mock.reserveStock(sagaId, any()) } throws RuntimeException("DB 연결 실패")

            send(reserveCommand(sagaId), sagaId)

            val reply = awaitReply(sagaId)
            assertNotNull(reply)
            assertFalse(reply.success)
            assertEquals("DB 연결 실패", reply.failureReason)
        }

        @Test
        fun `proto items 필드가 SagaItem 으로 올바르게 변환되어 service 에 전달`() {
            val sagaId = uniqueSagaId()
            coEvery { mockConfig.mock.reserveStock(any(), any()) } returns true

            send(
                inventoryCommand {
                    this.sagaId = sagaId
                    type = InventoryCommandType.RESERVE
                    items.add(inventoryCommandItem { productId = "prod-A"; optionId = "opt-B"; quantity = 7 })
                },
                sagaId
            )

            awaitReply(sagaId) // 처리 완료 대기

            coVerify {
                mockConfig.mock.reserveStock(
                    sagaId,
                    match { items ->
                        items.size == 1 &&
                            items[0].productId == "prod-A" &&
                            items[0].optionId == "opt-B" &&
                            items[0].quantity == 7
                    }
                )
            }
        }
    }

    // ──────────────────────────────────────────
    // RELEASE 커맨드
    // ──────────────────────────────────────────

    @Nested
    inner class Release {

        @Test
        fun `재고 해제 성공 시 success=true reply 발행`() {
            val sagaId = uniqueSagaId()
            coEvery { mockConfig.mock.releaseStock(sagaId, any()) } just runs

            send(releaseCommand(sagaId), sagaId)

            val reply = awaitReply(sagaId)
            assertNotNull(reply)
            assertTrue(reply.success)
            assertEquals(sagaId, reply.sagaId)
        }

        @Test
        fun `service 예외 발생 시 success=false + 예외 메시지 포함 reply 발행`() {
            val sagaId = uniqueSagaId()
            coEvery { mockConfig.mock.releaseStock(sagaId, any()) } throws RuntimeException("해제 실패")

            send(releaseCommand(sagaId), sagaId)

            val reply = awaitReply(sagaId)
            assertNotNull(reply)
            assertFalse(reply.success)
            assertEquals("해제 실패", reply.failureReason)
        }
    }

    // ──────────────────────────────────────────
    // 알 수 없는 커맨드 타입
    // ──────────────────────────────────────────

    @Nested
    inner class UnknownCommand {

        @Test
        fun `UNSPECIFIED 타입 수신 시 success=false reply 발행`() {
            val sagaId = uniqueSagaId()

            send(
                inventoryCommand {
                    this.sagaId = sagaId
                    type = InventoryCommandType.INVENTORY_COMMAND_TYPE_UNSPECIFIED
                },
                sagaId
            )

            val reply = awaitReply(sagaId)
            assertNotNull(reply)
            assertFalse(reply.success)
            assertTrue(reply.failureReason.isNotBlank())
        }
    }

    // ──────────────────────────────────────────
    // 헬퍼 함수
    // ──────────────────────────────────────────

    /**
     * inventory-command 토픽으로 커맨드 발행.
     * .get() 으로 producer ack 까지 블로킹하여
     * 메시지가 Kafka 에 쓰인 이후 테스트 로직이 진행됨을 보장한다.
     */
    private fun send(command: InventoryCommand, sagaId: String) {
        kafkaTemplate.send("inventory-command", sagaId, command).get(5, TimeUnit.SECONDS)
    }

    /**
     * inventory-reply 토픽에서 sagaId 에 해당하는 reply 를 폴링·반환.
     *
     * - earliest 리셋: 핸들러가 빠르게 처리해도 메시지를 놓치지 않음
     * - sagaId 필터링: 이전 테스트의 reply 와 혼재 방지
     * - UUID group.id: 테스트마다 committed offset 없이 시작
     */
    private fun awaitReply(sagaId: String, timeoutMs: Long = 15_000): InventoryReply? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val records = replyConsumer.poll(Duration.ofMillis(500))
            val matched = records.find { it.key() == sagaId }
            if (matched != null) return matched.value() as? InventoryReply
        }
        return null
    }

    private fun replyConsumerProps() = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
        ConsumerConfig.GROUP_ID_CONFIG to "test-reply-${UUID.randomUUID()}",
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to KafkaProtobufDeserializer::class.java.name,
        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl,
        KafkaProtobufDeserializerConfig.DERIVE_TYPE_CONFIG to "true",
    )

    private fun reserveCommand(sagaId: String) = inventoryCommand {
        this.sagaId = sagaId
        type = InventoryCommandType.RESERVE
        items.add(inventoryCommandItem { productId = "prod-1"; optionId = "opt-1"; quantity = 10 })
    }

    private fun releaseCommand(sagaId: String) = inventoryCommand {
        this.sagaId = sagaId
        type = InventoryCommandType.RELEASE
        items.add(inventoryCommandItem { productId = "prod-1"; optionId = "opt-1"; quantity = 10 })
    }

    private fun uniqueSagaId() = UUID.randomUUID().toString()
}
