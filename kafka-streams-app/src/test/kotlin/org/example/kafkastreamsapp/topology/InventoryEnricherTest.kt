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
 * InventoryEnricher 검증 — TopologyTestDriver 사용.
 *
 * 검증 범위:
 *  - stockStatus 계산 로직 (SOLD_OUT / LOW / NORMAL)
 *  - products-store GlobalKTable 조인으로 productName, category, imageUrl 보강
 *  - 상품 미존재 시 graceful 처리 (빈 문자열 / null)
 *  - optionId 선택적 필드 처리
 *  - __deleted:true → tombstone 출력
 *
 * [stockStatus 계산 규칙]
 *   currentStock == 0                           → SOLD_OUT
 *   (currentStock - reservedStock) <= safetyStock → LOW
 *   else                                        → NORMAL
 */
class InventoryEnricherTest {

    private lateinit var testDriver: TopologyTestDriver
    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private lateinit var inventoryCdcInput: TestInputTopic<String, String>
    private lateinit var productsGktInput: TestInputTopic<String, String>
    private lateinit var enrichedInventoryOutput: TestOutputTopic<String, String>

    @BeforeEach
    fun setUp() {
        val builder = StreamsBuilder()
        EnrichmentTopology(mapper).buildTopology(builder)
        testDriver = TopologyTestDriver(builder.build(), Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, "test-inventory-enricher")
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092")
            put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String()::class.java)
            put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String()::class.java)
        })

        inventoryCdcInput = testDriver.createInputTopic(
            EnrichmentTopology.INVENTORY_CDC, Serdes.String().serializer(), Serdes.String().serializer()
        )
        productsGktInput = testDriver.createInputTopic(
            EnrichmentTopology.ENRICHED_PRODUCTS, Serdes.String().serializer(), Serdes.String().serializer()
        )
        enrichedInventoryOutput = testDriver.createOutputTopic(
            EnrichmentTopology.ENRICHED_INVENTORY, Serdes.String().deserializer(), Serdes.String().deserializer()
        )
    }

    @AfterEach
    fun tearDown() {
        testDriver.close()
    }

    // ── stockStatus 계산 ──────────────────────────────────────────────────────

    @Nested
    inner class StockStatusCalculation {

        @Test
        fun `currentStock이 0이면 SOLD_OUT`() {
            inventoryCdcInput.pipeInput(
                debeziumKey("inv-1"),
                inventoryCdcJson("inv-1", "prod-1", currentStock = 0, reservedStock = 0, safetyStock = 5)
            )

            assertEquals("SOLD_OUT", readStockStatus())
        }

        @Test
        fun `가용재고(current - reserved)가 safetyStock 이하면 LOW`() {
            // current=8, reserved=5 → available=3, safety=5 → 3 <= 5 → LOW
            inventoryCdcInput.pipeInput(
                debeziumKey("inv-2"),
                inventoryCdcJson("inv-2", "prod-2", currentStock = 8, reservedStock = 5, safetyStock = 5)
            )

            assertEquals("LOW", readStockStatus())
        }

        @Test
        fun `가용재고가 safetyStock 정확히 같으면 LOW`() {
            // current=10, reserved=5 → available=5, safety=5 → 5 <= 5 → LOW
            inventoryCdcInput.pipeInput(
                debeziumKey("inv-3"),
                inventoryCdcJson("inv-3", "prod-3", currentStock = 10, reservedStock = 5, safetyStock = 5)
            )

            assertEquals("LOW", readStockStatus())
        }

        @Test
        fun `가용재고가 safetyStock 초과하면 NORMAL`() {
            // current=100, reserved=10 → available=90, safety=5 → 90 > 5 → NORMAL
            inventoryCdcInput.pipeInput(
                debeziumKey("inv-4"),
                inventoryCdcJson("inv-4", "prod-4", currentStock = 100, reservedStock = 10, safetyStock = 5)
            )

            assertEquals("NORMAL", readStockStatus())
        }

        private fun readStockStatus(): String =
            mapper.readTree(enrichedInventoryOutput.readValue()).path("stockStatus").asText()
    }

    // ── Product GlobalKTable 조인 ─────────────────────────────────────────────

    @Nested
    inner class ProductJoin {

        @Test
        fun `products-store에 상품이 있으면 productName, category, imageUrl이 enrichment된다`() {
            // Given
            productsGktInput.pipeInput(
                "prod-5",
                enrichedProductJson("prod-5", "Running Shoes", "Sports", "http://img/shoes.jpg")
            )

            inventoryCdcInput.pipeInput(
                debeziumKey("inv-5"),
                inventoryCdcJson("inv-5", "prod-5", currentStock = 50, reservedStock = 5, safetyStock = 10)
            )

            val node = mapper.readTree(enrichedInventoryOutput.readValue())
            assertEquals("inv-5", node.path("inventoryId").asText())
            assertEquals("prod-5", node.path("productId").asText())
            assertEquals("Running Shoes", node.path("productName").asText())
            assertEquals("Sports", node.path("category").asText())
            assertEquals("http://img/shoes.jpg", node.path("imageUrl").asText())
            assertEquals(50, node.path("currentStock").asInt())
            assertEquals(5, node.path("reservedStock").asInt())
            assertEquals("WAREHOUSE_A", node.path("location").asText())
        }

        @Test
        fun `products-store에 상품이 없으면 productName과 category는 빈 문자열, imageUrl은 null`() {
            inventoryCdcInput.pipeInput(
                debeziumKey("inv-6"),
                inventoryCdcJson("inv-6", "prod-unknown", currentStock = 20, reservedStock = 0, safetyStock = 5)
            )

            val node = mapper.readTree(enrichedInventoryOutput.readValue())
            assertEquals("", node.path("productName").asText())
            assertEquals("", node.path("category").asText())
            assertTrue(node.path("imageUrl").isNull)
        }
    }

    // ── 선택적 필드 처리 ──────────────────────────────────────────────────────

    @Nested
    inner class OptionalFields {

        @Test
        fun `optionId가 있을 때 결과에 포함된다`() {
            inventoryCdcInput.pipeInput(
                debeziumKey("inv-7"),
                inventoryCdcJsonWithOption(
                    "inv-7", "prod-7", optionId = "option-red",
                    currentStock = 30, reservedStock = 5, safetyStock = 10
                )
            )

            val node = mapper.readTree(enrichedInventoryOutput.readValue())
            assertEquals("option-red", node.path("optionId").asText())
        }

        @Test
        fun `optionId가 없을 때 결과에 null로 포함된다`() {
            inventoryCdcInput.pipeInput(
                debeziumKey("inv-8"),
                inventoryCdcJson("inv-8", "prod-8", currentStock = 30, reservedStock = 5, safetyStock = 10)
            )

            val node = mapper.readTree(enrichedInventoryOutput.readValue())
            assertTrue(node.path("optionId").isNull)
        }
    }

    // ── Tombstone ─────────────────────────────────────────────────────────────

    @Test
    fun `__deleted true이면 tombstone(null value)이 출력된다`() {
        inventoryCdcInput.pipeInput(
            debeziumKey("inv-del"),
            """{"__deleted":"true","_id":"inv-del"}"""
        )

        val record = enrichedInventoryOutput.readRecord()
        assertEquals("inv-del", record.key)
        assertNull(record.value)
    }

    // ── Fixture 헬퍼 ──────────────────────────────────────────────────────────

    private fun debeziumKey(id: String) = """{"_id":"$id"}"""

    private fun inventoryCdcJson(
        id: String, productId: String,
        currentStock: Int, reservedStock: Int, safetyStock: Int
    ): String = mapper.writeValueAsString(mapOf(
        "_id" to id, "productId" to productId,
        "currentStock" to currentStock, "reservedStock" to reservedStock,
        "safetyStock" to safetyStock, "location" to "WAREHOUSE_A"
    ))

    private fun inventoryCdcJsonWithOption(
        id: String, productId: String, optionId: String,
        currentStock: Int, reservedStock: Int, safetyStock: Int
    ): String = mapper.writeValueAsString(mapOf(
        "_id" to id, "productId" to productId, "optionId" to optionId,
        "currentStock" to currentStock, "reservedStock" to reservedStock,
        "safetyStock" to safetyStock, "location" to "WAREHOUSE_A"
    ))

    /** GlobalKTable(enriched-products)에 적재할 이미 enriched된 상품 JSON */
    private fun enrichedProductJson(
        id: String, name: String, category: String, imageUrl: String
    ): String = mapper.writeValueAsString(mapOf(
        "productId" to id, "name" to name, "category" to category, "imageUrl" to imageUrl,
        "salePrice" to 50000.0, "status" to "ACTIVE"
    ))
}
