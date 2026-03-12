package org.example.kafkastreamsapp.topology

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.TestOutputTopic
import org.apache.kafka.streams.TopologyTestDriver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Products / Payments / Deliveries 단순 변환 파이프라인 테스트.
 *
 * 검증 범위:
 *  - Debezium key {"_id":"..."} → plain string key 추출
 *  - CDC 문서 → enriched JSON 필드 변환
 *  - __deleted:true → tombstone(null value) 출력
 *  - priceRangeBucket, salePrice fallback 등 파생 필드 계산
 */
class EnrichmentTopologyTest {

    private lateinit var testDriver: TopologyTestDriver
    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @BeforeEach
    fun setUp() {
        val builder = StreamsBuilder()
        EnrichmentTopology(mapper).buildTopology(builder)
        testDriver = TopologyTestDriver(builder.build(), streamsProps("test-enrichment"))
    }

    @AfterEach
    fun tearDown() {
        testDriver.close()
    }

    // ── 공통 유틸 ──────────────────────────────────────────────────────────────

    private fun streamsProps(appId: String) = Properties().apply {
        put(StreamsConfig.APPLICATION_ID_CONFIG, appId)
        put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092")
        put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String()::class.java)
        put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String()::class.java)
    }

    private fun inputTopic(topic: String): TestInputTopic<String, String> =
        testDriver.createInputTopic(topic, Serdes.String().serializer(), Serdes.String().serializer())

    private fun outputTopic(topic: String): TestOutputTopic<String, String> =
        testDriver.createOutputTopic(topic, Serdes.String().deserializer(), Serdes.String().deserializer())

    private fun debeziumKey(id: String) = """{"_id":"$id"}"""

    // ── 1. Products 파이프라인 ─────────────────────────────────────────────────

    @Nested
    inner class ProductsPipeline {

        @Test
        fun `Debezium JSON key가 plain string id로 추출된다`() {
            val output = outputTopic(EnrichmentTopology.ENRICHED_PRODUCTS)
            inputTopic(EnrichmentTopology.PRODUCTS_CDC)
                .pipeInput(debeziumKey("prod-1"), productCdcJson("prod-1"))

            assertEquals("prod-1", output.readRecord().key)
        }

        @Test
        fun `상품 필드가 올바르게 enrichment된다`() {
            val output = outputTopic(EnrichmentTopology.ENRICHED_PRODUCTS)
            inputTopic(EnrichmentTopology.PRODUCTS_CDC).pipeInput(
                debeziumKey("prod-1"),
                productCdcJson("prod-1", name = "Nike Air Max", brand = "Nike",
                    category = "Shoes", tags = listOf("running", "sports"), salePrice = 129000.0)
            )

            val node = mapper.readTree(output.readValue())
            assertEquals("prod-1", node.path("productId").asText())
            assertEquals("Nike Air Max", node.path("name").asText())
            assertEquals(129000.0, node.path("salePrice").asDouble(), 0.01)
            assertEquals("Shoes", node.path("category").asText())
            assertEquals("MID", node.path("priceRangeBucket").asText())
            // fullTextSearch에 name, brand, category, tags가 모두 포함되어야 한다
            val fullText = node.path("fullTextSearch").asText()
            assertTrue(fullText.contains("Nike Air Max"))
            assertTrue(fullText.contains("Nike"))
            assertTrue(fullText.contains("running"))
        }

        @Test
        fun `salePrice 없을 때 basePrice로 대체된다`() {
            val output = outputTopic(EnrichmentTopology.ENRICHED_PRODUCTS)
            // salePrice 필드 없이 basePrice만 있는 경우
            val json = mapper.writeValueAsString(mapOf(
                "_id" to "prod-2", "name" to "Bag", "brand" to "B", "category" to "Bags",
                "basePrice" to 45000.0, "tags" to emptyList<String>(),
                "imageUrl" to "", "status" to "ACTIVE", "sellerId" to "seller-1"
            ))
            inputTopic(EnrichmentTopology.PRODUCTS_CDC).pipeInput(debeziumKey("prod-2"), json)

            val node = mapper.readTree(output.readValue())
            assertEquals(45000.0, node.path("salePrice").asDouble(), 0.01)
        }

        @Test
        fun `priceRangeBucket - LOW(50000 미만), MID(50000~499999), HIGH(500000 이상)`() {
            val input = inputTopic(EnrichmentTopology.PRODUCTS_CDC)
            val output = outputTopic(EnrichmentTopology.ENRICHED_PRODUCTS)

            input.pipeInput(debeziumKey("p-low"), productCdcJson("p-low", salePrice = 49999.0))
            input.pipeInput(debeziumKey("p-mid"), productCdcJson("p-mid", salePrice = 50000.0))
            input.pipeInput(debeziumKey("p-high"), productCdcJson("p-high", salePrice = 500000.0))

            assertEquals("LOW", mapper.readTree(output.readValue()).path("priceRangeBucket").asText())
            assertEquals("MID", mapper.readTree(output.readValue()).path("priceRangeBucket").asText())
            assertEquals("HIGH", mapper.readTree(output.readValue()).path("priceRangeBucket").asText())
        }

        @Test
        fun `__deleted true이면 tombstone(null value)이 출력된다`() {
            val output = outputTopic(EnrichmentTopology.ENRICHED_PRODUCTS)
            inputTopic(EnrichmentTopology.PRODUCTS_CDC)
                .pipeInput(debeziumKey("prod-del"), """{"__deleted":"true","_id":"prod-del"}""")

            val record = output.readRecord()
            assertEquals("prod-del", record.key)
            assertNull(record.value)
        }
    }

    // ── 2. Payments 파이프라인 ────────────────────────────────────────────────

    @Nested
    inner class PaymentsPipeline {

        @Test
        fun `결제 필드가 올바르게 enrichment된다`() {
            val output = outputTopic(EnrichmentTopology.ENRICHED_PAYMENTS)
            inputTopic(EnrichmentTopology.PAYMENTS_CDC)
                .pipeInput(debeziumKey("pay-1"), paymentCdcJson("pay-1", orderId = "order-1"))

            val node = mapper.readTree(output.readValue())
            assertEquals("pay-1", node.path("paymentId").asText())
            assertEquals("order-1", node.path("orderId").asText())
            assertEquals("CREDIT_CARD", node.path("method").asText())
            assertEquals("TOSS_PAYMENTS", node.path("pgProvider").asText())
            assertEquals(99000.0, node.path("totalAmount").asDouble(), 0.01)
        }

        @Test
        fun `__deleted true이면 tombstone(null value)이 출력된다`() {
            val output = outputTopic(EnrichmentTopology.ENRICHED_PAYMENTS)
            inputTopic(EnrichmentTopology.PAYMENTS_CDC)
                .pipeInput(debeziumKey("pay-del"), """{"__deleted":"true","_id":"pay-del"}""")

            assertNull(output.readRecord().value)
        }
    }

    // ── 3. Deliveries 파이프라인 ──────────────────────────────────────────────

    @Nested
    inner class DeliveriesPipeline {

        @Test
        fun `배송 필드와 items 배열이 올바르게 enrichment된다`() {
            val output = outputTopic(EnrichmentTopology.ENRICHED_DELIVERIES)
            inputTopic(EnrichmentTopology.DELIVERIES_CDC)
                .pipeInput(debeziumKey("del-1"), deliveryCdcJson("del-1", orderId = "order-1"))

            val node = mapper.readTree(output.readValue())
            assertEquals("del-1", node.path("deliveryId").asText())
            assertEquals("order-1", node.path("orderId").asText())
            assertEquals("Hong Gildong", node.path("recipientName").asText())
            assertTrue(node.path("items").isArray)
            assertEquals(1, node.path("items").size())
            assertEquals("prod-1", node.path("items")[0].path("productId").asText())
            assertEquals(2, node.path("items")[0].path("quantity").asInt())
        }

        @Test
        fun `__deleted true이면 tombstone(null value)이 출력된다`() {
            val output = outputTopic(EnrichmentTopology.ENRICHED_DELIVERIES)
            inputTopic(EnrichmentTopology.DELIVERIES_CDC)
                .pipeInput(debeziumKey("del-del"), """{"__deleted":"true","_id":"del-del"}""")

            assertNull(output.readRecord().value)
        }
    }

    // ── CDC Fixture 생성 헬퍼 ──────────────────────────────────────────────────

    private fun productCdcJson(
        id: String,
        name: String = "Test Product",
        brand: String = "Brand",
        category: String = "Category",
        tags: List<String> = listOf("tag1"),
        salePrice: Double = 99000.0
    ): String = mapper.writeValueAsString(mapOf(
        "_id" to id, "name" to name, "brand" to brand, "category" to category,
        "tags" to tags, "salePrice" to salePrice, "basePrice" to salePrice,
        "imageUrl" to "http://img.test/1.jpg", "status" to "ACTIVE", "sellerId" to "seller-1"
    ))

    private fun paymentCdcJson(id: String, orderId: String): String =
        mapper.writeValueAsString(mapOf(
            "_id" to id, "orderId" to orderId, "userId" to "user-1",
            "totalAmount" to 99000.0, "canceledAmount" to 0.0,
            "status" to "APPROVED", "method" to "CREDIT_CARD", "pgProvider" to "TOSS_PAYMENTS"
        ))

    private fun deliveryCdcJson(id: String, orderId: String): String =
        mapper.writeValueAsString(mapOf(
            "_id" to id, "orderId" to orderId, "userId" to "user-1",
            "destination" to mapOf("receiverName" to "Hong Gildong", "address" to "Seoul"),
            "items" to listOf(
                mapOf("productId" to "prod-1", "productName" to "Test Product", "quantity" to 2)
            ),
            "status" to "PREPARING"
        ))
}
