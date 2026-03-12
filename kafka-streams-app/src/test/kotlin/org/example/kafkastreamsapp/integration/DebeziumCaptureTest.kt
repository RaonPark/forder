package org.example.kafkastreamsapp.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.MongoClients
import io.debezium.engine.ChangeEvent
import io.debezium.engine.DebeziumEngine
import io.debezium.engine.format.Json
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layer 1 — Debezium Embedded Engine + MongoDB TestContainer 통합 테스트.
 *
 * Kafka Connect 없이 Debezium 라이브러리를 test JVM 안에서 직접 실행한다.
 * MongoDB 변경 이벤트가 올바른 형식으로 캡처되는지 검증한다.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  [테스트 파이프라인]                                                       │
 * │                                                                         │
 * │  MongoDB (TestContainer)                                                │
 * │      └→ Debezium Embedded Engine  ← ExtractNewDocumentState SMT 적용   │
 * │              └→ CopyOnWriteArrayList (in-memory)                        │
 * │                      └→ test assertions                                 │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * [SMT가 왜 필요한가]
 *   SMT 없을 때  key: {"id":"order-1"}          (Debezium 내부 schema 형식)
 *   SMT 적용 후  key: {"_id":"order-1"}          → extractId()가 기대하는 형식
 *                value: flat doc + __deleted:true → isDeleted()가 기대하는 형식
 *
 * [Replica Set 구성]
 *   TC 2.0의 MongoDBContainer는 replica set을 자동 설정하지 않는다 (TC 1.19+와 다름).
 *   Debezium change stream은 replica set이 필수이므로 GenericContainer로 직접 구성한다.
 *
 *   - mongod를 --replSet rs0 플래그로 시작
 *   - rs.initiate()로 단일 노드 replica set 초기화
 *   - member hostname을 host.docker.internal:mappedPort로 설정하여
 *     컨테이너 내부(self-connection)와 호스트 JVM(replica set discovery) 양쪽에서 접근 가능
 *
 *   Connection string 분리:
 *   - Debezium용: replicaSet=rs0 (replica set discovery 필요)
 *   - 테스트 헬퍼용: directConnection=true (단순 CRUD, discovery 불필요)
 */
@Testcontainers
class DebeziumCaptureTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = GenericContainer(DockerImageName.parse("mongo:latest"))
            .withExposedPorts(27017)
            .withCommand("mongod", "--replSet", "rs0", "--bind_ip_all")

        /** Debezium용 — replicaSet discovery 포함 */
        private lateinit var debeziumUri: String
        /** 테스트 헬퍼용 — directConnection (단순 CRUD) */
        private lateinit var helperUri: String

        @JvmStatic
        @BeforeAll
        fun initReplicaSet() {
            val hostPort = mongo.getMappedPort(27017)

            // member hostname을 host.docker.internal:mappedPort로 설정.
            // 컨테이너 내부: host.docker.internal → 호스트 → Docker 포트 포워딩 → 컨테이너 (self-connection)
            // 호스트 JVM: host.docker.internal → localhost → Docker 포트 포워딩 → 컨테이너 (discovery)
            val initResult = mongo.execInContainer(
                "mongosh", "--quiet", "--eval",
                """rs.initiate({_id:"rs0",members:[{_id:0,host:"host.docker.internal:$hostPort"}]})"""
            )
            println("[RS INIT] stdout=${initResult.stdout} stderr=${initResult.stderr}")

            // Primary election 완료 대기
            Thread.sleep(3_000)

            debeziumUri = "mongodb://host.docker.internal:$hostPort/?replicaSet=rs0"
            helperUri = "mongodb://localhost:$hostPort/?directConnection=true"
        }
    }

    private val mapper = ObjectMapper()
    private val executor = Executors.newSingleThreadExecutor()
    private var engine: DebeziumEngine<ChangeEvent<String, String>>? = null

    @AfterEach
    fun tearDown() {
        engine?.close()
        executor.shutdown()
    }

    // ── 1. INSERT ─────────────────────────────────────────────────────────────

    @Test
    fun `MongoDB insert가 key=id 형태의 CDC 이벤트로 캡처된다`() {
        val events = CopyOnWriteArrayList<ChangeEvent<String, String>>()
        val latch = CountDownLatch(1)

        engine = buildEngine("orders") { event ->
            if (event.value() != null) {
                events.add(event)
                latch.countDown()
            }
        }
        executor.submit(engine)
        awaitEngineReady()

        insertDocument("orders", mapOf(
            "_id" to "order-1", "userId" to "user-1",
            "status" to "PENDING", "totalAmount" to 99000
        ))

        assertTrue(latch.await(20, TimeUnit.SECONDS), "INSERT CDC 이벤트가 20초 내 캡처되어야 함")

        val event = events.first()
        println("[CDC Key]   ${event.key()}")
        println("[CDC Value] ${event.value()}")

        // ExtractNewDocumentState SMT → key: {"id":"order-1"}
        // EnrichmentTopology.extractId()가 id 필드를 읽는다
        val keyNode = mapper.readTree(event.key())
        assertEquals("order-1", keyNode.path("payload").path("id").asText())

        val valueNode = mapper.readTree(event.value())
        assertEquals("PENDING", valueNode.path("payload").path("status").asText())
        assertEquals(99000, valueNode.path("payload").path("totalAmount").asInt())
    }

    // ── 2. UPDATE ─────────────────────────────────────────────────────────────

    @Test
    fun `MongoDB update 이벤트에 capture_mode=change_streams_update_full로 전체 문서가 포함된다`() {
        val updateEvents = CopyOnWriteArrayList<ChangeEvent<String, String>>()
        val latch = CountDownLatch(1)
        var insertSkipped = false

        engine = buildEngine("payments") { event ->
            if (event.value() == null) return@buildEngine
            // 첫 번째 이벤트(insert/snapshot)는 건너뜀
            if (!insertSkipped) { insertSkipped = true; return@buildEngine }
            val node = mapper.readTree(event.value())
            if (node.path("payload").path("status").asText() == "APPROVED") {
                updateEvents.add(event)
                latch.countDown()
            }
        }
        executor.submit(engine)
        awaitEngineReady()

        MongoClients.create(helperUri).use { client ->
            val col = client.getDatabase("forder").getCollection("payments")
            col.insertOne(Document(mapOf("_id" to "pay-1", "status" to "PENDING", "amount" to 50000)))
            Thread.sleep(500)
            col.updateOne(
                Document("_id", "pay-1"),
                Document("\$set", Document("status", "APPROVED"))
            )
        }

        assertTrue(latch.await(20, TimeUnit.SECONDS), "UPDATE CDC 이벤트가 20초 내 캡처되어야 함")

        val valueNode = mapper.readTree(updateEvents.first().value())
        // capture.mode=change_streams_update_full: 수정된 필드뿐 아니라 전체 문서 포함
        assertEquals("APPROVED", valueNode.path("payload").path("status").asText())
        assertEquals(50000, valueNode.path("payload").path("amount").asInt()) // 수정하지 않은 필드도 포함
    }

    // ── 3. DELETE ─────────────────────────────────────────────────────────────

    @Test
    fun `MongoDB delete가 __deleted=true 이벤트로 캡처된다`() {
        val deleteEvents = CopyOnWriteArrayList<ChangeEvent<String, String>>()
        val latch = CountDownLatch(1)

        engine = buildEngine("inventory") { event ->
            val value = event.value() ?: return@buildEngine
            if (mapper.readTree(value).path("payload").path("__deleted").asText() == "true") {
                deleteEvents.add(event)
                latch.countDown()
            }
        }
        executor.submit(engine)
        awaitEngineReady()

        MongoClients.create(helperUri).use { client ->
            val col = client.getDatabase("forder").getCollection("inventory")
            col.insertOne(Document("_id", "inv-del").append("stock", 100))
            Thread.sleep(500)
            col.deleteOne(Document("_id", "inv-del"))
        }

        assertTrue(latch.await(20, TimeUnit.SECONDS), "DELETE CDC 이벤트가 20초 내 캡처되어야 함")

        val event = deleteEvents.first()
        // key에 삭제된 문서의 id 포함 (SMT가 _id → id로 변환)
        assertEquals("inv-del", mapper.readTree(event.key()).path("payload").path("id").asText())
        // value의 __deleted:true → EnrichmentTopology.isDeleted()가 tombstone 처리에 사용
        assertEquals("true", mapper.readTree(event.value()).path("payload").path("__deleted").asText())
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /**
     * Debezium Embedded Engine 생성.
     * 프로덕션 Kafka Connect의 source connector 설정과 동일한 파라미터를 사용한다.
     */
    private fun buildEngine(
        collection: String,
        handler: (ChangeEvent<String, String>) -> Unit
    ): DebeziumEngine<ChangeEvent<String, String>> = DebeziumEngine.create(Json::class.java)
        .using(Properties().apply {
            put("name", "capture-test-$collection-${System.currentTimeMillis()}")
            put("connector.class", "io.debezium.connector.mongodb.MongoDbConnector")
            // 파일 없이 메모리에 오프셋 저장 (테스트 격리)
            put("offset.storage", "org.apache.kafka.connect.storage.MemoryOffsetBackingStore")
            put("offset.flush.interval.ms", "0")

            // MongoDB 연결 — Debezium은 replicaSet discovery가 필요
            put("mongodb.connection.string", debeziumUri)
            put("topic.prefix", "${collection}s-cdc")
            put("database.include.list", "forder")
            put("collection.include.list", "forder.$collection")

            // update 이벤트에 전체 문서 포함 (프로덕션 설정과 동일)
            put("capture.mode", "change_streams_update_full")
            // 기존 문서 스냅샷 없이 변경 이벤트만 캡처
            put("snapshot.mode", "no_data")

            // ── ExtractNewDocumentState SMT ────────────────────────────────
            // 프로덕션 Kafka Connect source connector의 transforms 설정과 동일.
            //
            // Debezium 3.4+: drop.tombstones + delete.handling.mode deprecated
            //   → delete.tombstone.handling.mode=rewrite 로 통합
            // rewrite: delete 시 value에 __deleted 필드 추가 (tombstone 대신)
            // ──────────────────────────────────────────────────────────────
            put("transforms", "unwrap")
            put("transforms.unwrap.type",
                "io.debezium.connector.mongodb.transforms.ExtractNewDocumentState")
            put("transforms.unwrap.delete.tombstone.handling.mode", "rewrite")
        })
        .notifying(handler)
        .using { success, message, error ->
            if (!success) {
                println("[Debezium ERROR] message=$message")
                error?.printStackTrace()
            }
        }
        .build()

    private fun insertDocument(collection: String, data: Map<String, Any>) {
        MongoClients.create(helperUri).use { client ->
            client.getDatabase("forder")
                .getCollection(collection)
                .insertOne(Document(data))
        }
    }

    /** Debezium 엔진이 MongoDB change stream을 구독 완료할 때까지 대기 */
    private fun awaitEngineReady(millis: Long = 5_000L) = Thread.sleep(millis)
}
