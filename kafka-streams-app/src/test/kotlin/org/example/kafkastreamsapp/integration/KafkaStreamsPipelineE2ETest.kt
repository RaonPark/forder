package org.example.kafkastreamsapp.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.mongodb.client.MongoClients
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.bson.Document
import org.example.kafkastreamsapp.topology.EnrichmentTopology
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Layer 2 — 전체 파이프라인 E2E 통합 테스트.
 *
 * 실제 프로덕션과 동일한 경로로 데이터가 흐르는지 검증한다.
 *
 * ┌────────────────────────────────────────────────────────────────────────────┐
 * │  [전체 파이프라인]                                                            │
 * │                                                                            │
 * │  MongoDB (TestContainer)                                                   │
 * │      └→ Kafka Connect + Debezium (TestContainer, quay.io/debezium/connect) │
 * │              └→ Kafka (TestContainer, KRaft)                               │
 * │                      └→ KafkaStreams (EnrichmentTopology, test JVM)        │
 * │                              └→ enriched-orders topic                      │
 * │                                      └→ KafkaConsumer → test assertions   │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * [컨테이너 네트워크 구성]
 *   forder-e2e-net (Docker bridge network)
 *   ├── mongo   (27017) — Debezium이 내부에서 mongo:27017로 접근
 *   ├── kafka   (9093)  — Connect가 내부에서 kafka:9093으로 접근
 *   └── connect (8083)  — test JVM이 외부 매핑 포트로 REST API 호출
 *
 * [Replica Set 구성]
 *   TC 2.0의 MongoDBContainer는 replica set을 자동 설정하지 않으므로
 *   GenericContainer + --replSet rs0 + rs.initiate()로 직접 구성한다.
 *   member hostname을 Docker network alias 'mongo:27017'로 설정하여
 *   Debezium Connect 컨테이너에서 replica set discovery가 가능하게 한다.
 */
@Testcontainers
class KafkaStreamsPipelineE2ETest {

    companion object {
        private val network: Network = Network.newNetwork()

        /**
         * MongoDB — GenericContainer로 replica set 직접 구성.
         * TC 2.0의 MongoDBContainer는 replica set을 자동 설정하지 않음.
         */
        @Container
        @JvmStatic
        val mongo = GenericContainer(DockerImageName.parse("mongo:latest"))
            .withExposedPorts(27017)
            .withCommand("mongod", "--replSet", "rs0", "--bind_ip_all")
            .withNetwork(network)
            .withNetworkAliases("mongo")

        @Container
        @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"))
            .withNetwork(network)
            .withNetworkAliases("kafka")

        /**
         * Debezium MongoDB 커넥터가 내장된 공식 Kafka Connect 이미지.
         * dependsOn으로 kafka, mongo가 먼저 기동된 후 시작된다.
         * /connectors REST endpoint로 준비 완료를 확인한다.
         */
        @Container
        @JvmStatic
        val connect = GenericContainer(DockerImageName.parse("quay.io/debezium/connect:3.4.2.Final"))
            .withNetwork(network)
            .withNetworkAliases("connect")
            .withExposedPorts(8083)
            // ── Kafka Connect 필수 설정 ──────────────────────────────────────
            // Docker 내부에서 kafka 네트워크 alias + 9093 포트로 Kafka 접근
            .withEnv("BOOTSTRAP_SERVERS", "kafka:9093")
            .withEnv("GROUP_ID", "e2e-connect-cluster")
            .withEnv("CONFIG_STORAGE_TOPIC", "_connect-configs")
            .withEnv("OFFSET_STORAGE_TOPIC", "_connect-offsets")
            .withEnv("STATUS_STORAGE_TOPIC", "_connect-status")
            .withEnv("CONFIG_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("OFFSET_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("STATUS_STORAGE_REPLICATION_FACTOR", "1")
            // ────────────────────────────────────────────────────────────────
            // Converter 설정은 커넥터 레벨에서 지정 (registerConnector 참조).
            // Debezium Docker 이미지의 worker env var는 VALUE_CONVERTER_SCHEMAS_ENABLE
            // 등 세부 속성을 인식하지 않으므로, 커넥터 config에 직접 설정해야 한다.
            // ────────────────────────────────────────────────────────────────
            .dependsOn(kafka, mongo)
            .waitingFor(
                Wait.forHttp("/connectors")
                    .forPort(8083)
                    .withStartupTimeout(Duration.ofSeconds(120))
            )

        /** 테스트 JVM용 MongoDB URI (directConnection — replica set discovery 불필요) */
        private lateinit var mongoUri: String

        @JvmStatic
        @BeforeAll
        fun initReplicaSet() {
            // Replica set member를 Docker network alias로 초기화.
            // Debezium Connect 컨테이너가 같은 네트워크에서 mongo:27017로 접근 + discovery 가능.
            val initResult = mongo.execInContainer(
                "mongosh", "--quiet", "--eval",
                """rs.initiate({_id:"rs0",members:[{_id:0,host:"mongo:27017"}]})"""
            )
            println("[RS INIT] stdout=${initResult.stdout} stderr=${initResult.stderr}")

            // Primary election 완료 대기
            Thread.sleep(3_000)

            mongoUri = "mongodb://localhost:${mongo.getMappedPort(27017)}/?directConnection=true"
        }
    }

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val http = HttpClient.newHttpClient()
    private lateinit var streams: KafkaStreams

    @BeforeEach
    fun setUp() {
        // 이전 테스트 잔재 정리
        runCatching { deleteConnector("orders-connector") }

        // Debezium MongoDB 커넥터 등록 (REST API)
        registerConnector(
            name = "orders-connector",
            topicPrefix = "orders-cdc",       // → orders-cdc.forder.orders (EnrichmentTopology.ORDERS_CDC 와 일치)
            collection = "forder.orders"
        )
        awaitConnectorRunning("orders-connector")

        // GlobalKTable 소스 토픽 사전 생성 (KafkaStreams 시작 전 필수)
        createGlobalKTableTopics()

        // KafkaStreams 시작 (EnrichmentTopology)
        streams = buildAndStartStreams()
    }

    @AfterEach
    fun tearDown() {
        streams.close()
        runCatching { deleteConnector("orders-connector") }
    }

    // ── 테스트 ─────────────────────────────────────────────────────────────────

    @Test
    fun `MongoDB 주문 insert가 Kafka Connect Debezium을 거쳐 enriched-orders에 저장된다`() {
        // given — enriched-orders 토픽 구독 (insert 이전에 subscribe해야 earliest 오프셋부터 읽음)
        val consumer = createConsumer(EnrichmentTopology.ENRICHED_ORDERS)

        // when — MongoDB에 주문 문서 삽입
        MongoClients.create(mongoUri).use { client ->
            client.getDatabase("forder").getCollection("orders").insertOne(
                Document(mapOf(
                    "_id" to "order-e2e-1",
                    "userId" to "user-1",
                    "status" to "PAYMENT_COMPLETED",
                    "totalAmount" to 99000,
                    "deliveryAddress" to "Seoul Gangnam-gu",
                    "receiverName" to "Hong Gildong",
                    "receiverPhone" to "010-0000-0000",
                    "items" to listOf(
                        Document(mapOf("productId" to "p-1", "quantity" to 2, "priceAtOrder" to 49500))
                    )
                ))
            )
        }

        // then — KafkaStreams가 enriched-orders 토픽에 결과를 기록할 때까지 대기
        val record = pollUntil(consumer, timeoutSec = 60) { record ->
            runCatching {
                mapper.readTree(record.value()).path("orderId").asText() == "order-e2e-1"
            }.getOrDefault(false)
        }
        consumer.close()

        // ── 진단 출력 (실패 시 원인 파악용) ──────────────────────────────────
        if (record == null) {
            println("[DIAG] KafkaStreams state: ${streams.state()}")

            // CDC 토픽에 이벤트가 도착했는지 확인
            val cdcConsumer = createConsumer(EnrichmentTopology.ORDERS_CDC)
            val cdcRecords = mutableListOf<ConsumerRecord<String, String>>()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                cdcRecords.addAll(cdcConsumer.poll(Duration.ofSeconds(1)))
            }
            println("[DIAG] CDC topic '${EnrichmentTopology.ORDERS_CDC}' records: ${cdcRecords.size}")
            cdcRecords.forEach { r ->
                println("[DIAG]   key=${r.key()}")
                println("[DIAG]   value=${r.value()?.take(300)}")
            }
            cdcConsumer.close()

            // enriched-orders 토픽에 어떤 레코드든 있는지 확인
            val enrichedConsumer = createConsumer(EnrichmentTopology.ENRICHED_ORDERS)
            val enrichedRecords = mutableListOf<ConsumerRecord<String, String>>()
            val deadline2 = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline2) {
                enrichedRecords.addAll(enrichedConsumer.poll(Duration.ofSeconds(1)))
            }
            println("[DIAG] enriched-orders records: ${enrichedRecords.size}")
            enrichedRecords.forEach { r ->
                println("[DIAG]   key=${r.key()} value=${r.value()?.take(300)}")
            }
            enrichedConsumer.close()
        }

        assertNotNull(record, "enriched-orders 토픽에 order-e2e-1 레코드가 있어야 함")
        val result = mapper.readTree(record.value())
        println("[E2E 결과]\n${result.toPrettyString()}")

        // 기본 주문 필드 검증
        assertEquals("order-e2e-1", result.path("orderId").asText())
        assertEquals("PAYMENT_COMPLETED", result.path("status").asText())
        assertEquals(99000.0, result.path("totalAmount").asDouble(), 0.01)

        // items 배열 검증 (GlobalKTable에 product 없으므로 productName은 빈 문자열)
        val items = result.path("items")
        assertTrue(items.isArray)
        assertEquals(1, items.size())
        assertEquals("p-1", items[0].path("productId").asText())
        assertEquals(2, items[0].path("quantity").asInt())
    }

    @Test
    fun `MongoDB 주문 delete가 enriched-orders에 tombstone으로 저장된다`() {
        val consumer = createConsumer(EnrichmentTopology.ENRICHED_ORDERS)

        MongoClients.create(mongoUri).use { client ->
            val col = client.getDatabase("forder").getCollection("orders")
            col.insertOne(Document(mapOf(
                "_id" to "order-del-1", "userId" to "user-1", "status" to "CANCELLED",
                "totalAmount" to 0, "deliveryAddress" to "Seoul", "receiverName" to "Test",
                "receiverPhone" to "010-0000-0000", "items" to emptyList<Document>()
            )))
            Thread.sleep(1000) // insert CDC 처리 대기
            col.deleteOne(Document("_id", "order-del-1"))
        }

        // tombstone: extractId()가 CDC key {"id":"order-del-1"} → "order-del-1"로 변환
        // enriched-orders 토픽에는 plain string key + null value로 저장
        val tombstone = pollUntil(consumer, timeoutSec = 60) { record ->
            record.key() == "order-del-1" && record.value() == null
        }
        consumer.close()

        assertNotNull(tombstone, "enriched-orders 토픽에 order-del-1 tombstone이 있어야 함")
    }

    // ── Kafka Connect REST API 헬퍼 ───────────────────────────────────────────

    private val connectUrl get() = "http://${connect.host}:${connect.getMappedPort(8083)}"

    /**
     * Debezium MongoDB 소스 커넥터 등록.
     *
     * topic.prefix 를 이용해 Debezium 출력 토픽명을 프로덕션 설정과 일치시킨다.
     *   topic.prefix=orders-cdc + database=forder + collection=orders
     *   → 출력 토픽: orders-cdc.forder.orders  (= EnrichmentTopology.ORDERS_CDC)
     */
    private fun registerConnector(name: String, topicPrefix: String, collection: String) {
        val config = mapper.writeValueAsString(mapOf(
            "name" to name,
            "config" to mapOf(
                "connector.class" to "io.debezium.connector.mongodb.MongoDbConnector",
                // Docker 내부 MongoDB 주소 (network alias) — replicaSet discovery 사용
                "mongodb.connection.string" to "mongodb://mongo:27017/?replicaSet=rs0",
                "topic.prefix" to topicPrefix,
                "database.include.list" to "forder",
                "collection.include.list" to collection,
                // update 이벤트에 전체 문서 포함
                "capture.mode" to "change_streams_update_full",
                // 기존 문서 스냅샷 생략 — 테스트 중 삽입되는 문서만 처리
                "snapshot.mode" to "no_data",
                // 토픽 자동 생성 (단일 브로커 테스트 환경)
                "topic.creation.default.replication.factor" to "1",
                "topic.creation.default.partitions" to "1",
                // ── Converter 설정 (커넥터 레벨) ──────────────────────────
                // Worker env var가 아닌 커넥터 config에서 지정해야
                // schemas.enable 등 세부 속성이 적용된다.
                "key.converter" to "org.apache.kafka.connect.json.JsonConverter",
                "key.converter.schemas.enable" to "false",
                "value.converter" to "org.apache.kafka.connect.json.JsonConverter",
                "value.converter.schemas.enable" to "false",
                // ── ExtractNewDocumentState SMT ────────────────────────────
                // Debezium 3.4+: drop.tombstones + delete.handling.mode deprecated
                //   → delete.tombstone.handling.mode=rewrite 로 통합
                "transforms" to "unwrap",
                "transforms.unwrap.type" to
                        "io.debezium.connector.mongodb.transforms.ExtractNewDocumentState",
                "transforms.unwrap.delete.tombstone.handling.mode" to "rewrite",
            )
        ))

        val response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("$connectUrl/connectors"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(config))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertTrue(response.statusCode() in 200..201,
            "커넥터 등록 실패 (${response.statusCode()}): ${response.body()}")
    }

    /** 커넥터 상태가 RUNNING이 될 때까지 폴링 */
    private fun awaitConnectorRunning(connectorName: String, timeoutSec: Long = 30) {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < deadline) {
            runCatching {
                val response = http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("$connectUrl/connectors/$connectorName/status"))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                if (response.statusCode() == 200) {
                    val state = mapper.readTree(response.body())
                        .path("connector").path("state").asText()
                    if (state == "RUNNING") return
                }
            }
            Thread.sleep(1000)
        }
        error("Connector '$connectorName' did not reach RUNNING within ${timeoutSec}s")
    }

    private fun deleteConnector(name: String) {
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("$connectUrl/connectors/$name"))
                .DELETE().build(),
            HttpResponse.BodyHandlers.ofString()
        )
    }

    // ── KafkaStreams 헬퍼 ─────────────────────────────────────────────────────

    private fun buildAndStartStreams(): KafkaStreams {
        val builder = StreamsBuilder()
        EnrichmentTopology(mapper).buildTopology(builder)

        val props = Properties().apply {
            // 테스트 실행마다 고유한 application.id (상태 저장소 충돌 방지)
            put(StreamsConfig.APPLICATION_ID_CONFIG, "e2e-streams-${System.currentTimeMillis()}")
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String()::class.java)
            put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String()::class.java)
            put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 1)         // 단일 브로커
            put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1)          // 테스트 안정성
            put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100)        // 빠른 커밋
        }

        val latch = CountDownLatch(1)
        val streams = KafkaStreams(builder.build(), props)
        streams.setStateListener { newState, _ ->
            if (newState == KafkaStreams.State.RUNNING) latch.countDown()
        }
        streams.start()
        assertTrue(latch.await(60, TimeUnit.SECONDS), "KafkaStreams가 60초 내에 RUNNING이 되어야 함")
        return streams
    }

    // ── KafkaConsumer 헬퍼 ────────────────────────────────────────────────────

    private fun createConsumer(topic: String): KafkaConsumer<String, String> {
        val consumer = KafkaConsumer<String, String>(Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            // 각 테스트마다 독립적인 consumer group → 오프셋 충돌 없음
            put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-consumer-${System.currentTimeMillis()}")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        })
        consumer.subscribe(listOf(topic))
        return consumer
    }

    /** predicate를 만족하는 레코드가 나올 때까지 timeoutSec 동안 폴링 */
    private fun pollUntil(
        consumer: KafkaConsumer<String, String>,
        timeoutSec: Long,
        predicate: (ConsumerRecord<String, String>) -> Boolean
    ): ConsumerRecord<String, String>? {
        val deadline = System.currentTimeMillis() + timeoutSec * 1_000
        while (System.currentTimeMillis() < deadline) {
            val records = consumer.poll(Duration.ofSeconds(1))
            for (record in records) {
                if (predicate(record)) return record
            }
        }
        return null
    }

    // ── KafkaTopic 헬퍼 ────────────────────────────────────────────────────

    /**
     * GlobalKTable 소스 토픽은 KafkaStreams 시작 전에 반드시 존재해야 한다.
     * 단일 브로커 테스트 환경이므로 replication.factor=1.
     */
    private fun createGlobalKTableTopics() {
        AdminClient.create(mapOf(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers
        )).use { admin ->
            val topics = listOf(
                // GlobalKTable 소스 토픽 — KafkaStreams 시작 전 필수
                EnrichmentTopology.ENRICHED_PRODUCTS,
                EnrichmentTopology.ENRICHED_PAYMENTS,
                EnrichmentTopology.ENRICHED_DELIVERIES,
                // Enrichment 출력 토픽 — 사전 생성으로 consumer partition 할당 지연 방지
                EnrichmentTopology.ENRICHED_ORDERS,
                EnrichmentTopology.ENRICHED_INVENTORY,
                // KStream 소스 CDC 토픽 — 이 테스트는 orders만 커넥터를 등록하지만
                // EnrichmentTopology가 5개 CDC 토픽 전부를 구독하므로 미리 생성
                EnrichmentTopology.ORDERS_CDC,
                EnrichmentTopology.PAYMENTS_CDC,
                EnrichmentTopology.DELIVERIES_CDC,
                EnrichmentTopology.INVENTORY_CDC,
                EnrichmentTopology.PRODUCTS_CDC,
            ).map { NewTopic(it, 1, 1.toShort()) }
            admin.createTopics(topics).all().get(30, TimeUnit.SECONDS)
            Thread.sleep(1000)
        }
    }
}
