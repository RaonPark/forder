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
import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OrderEnricher 통합 검증 — TopologyTestDriver 사용.
 *
 * GlobalKTable 3개(products-store, payments-store, deliveries-store) 조인 로직을 검증한다.
 *
 * [테스트 데이터 흐름]
 *   productsGktInput  → enriched-products  → products-store   ┐
 *   paymentsGktInput  → enriched-payments  → payments-store   ├→ OrderEnricher
 *   deliveriesGktInput→ enriched-deliveries→ deliveries-store ┘
 *   ordersCdcInput    → orders-cdc.forder.orders ─────────────→ enriched-orders
 */
class OrderEnricherTest {

    private lateinit var testDriver: TopologyTestDriver
    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private lateinit var ordersCdcInput: TestInputTopic<String, String>
    private lateinit var productsGktInput: TestInputTopic<String, String>
    private lateinit var paymentsGktInput: TestInputTopic<String, String>
    private lateinit var deliveriesGktInput: TestInputTopic<String, String>
    private lateinit var enrichedOrdersOutput: TestOutputTopic<String, String>

    @BeforeEach
    fun setUp() {
        val builder = StreamsBuilder()
        EnrichmentTopology(mapper).buildTopology(builder)
        testDriver = TopologyTestDriver(builder.build(), Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, "test-order-enricher")
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092")
            put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String()::class.java)
            put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String()::class.java)
        })

        ordersCdcInput = testDriver.createInputTopic(
            EnrichmentTopology.ORDERS_CDC, Serdes.String().serializer(), Serdes.String().serializer()
        )
        // GlobalKTable 소스 토픽 — pipeInput 즉시 상태 저장소에 반영됨 (동기식)
        productsGktInput = testDriver.createInputTopic(
            EnrichmentTopology.ENRICHED_PRODUCTS, Serdes.String().serializer(), Serdes.String().serializer()
        )
        paymentsGktInput = testDriver.createInputTopic(
            EnrichmentTopology.ENRICHED_PAYMENTS, Serdes.String().serializer(), Serdes.String().serializer()
        )
        deliveriesGktInput = testDriver.createInputTopic(
            EnrichmentTopology.ENRICHED_DELIVERIES, Serdes.String().serializer(), Serdes.String().serializer()
        )
        enrichedOrdersOutput = testDriver.createOutputTopic(
            EnrichmentTopology.ENRICHED_ORDERS, Serdes.String().deserializer(), Serdes.String().deserializer()
        )
    }

    @AfterEach
    fun tearDown() {
        testDriver.close()
    }

    @Test
    fun `GlobalKTable 3개 모두 있을 때 주문이 완전히 enrichment된다`() {
        // Given — GlobalKTable 상태 저장소에 데이터 적재 (key: plain entity id)
        productsGktInput.pipeInput("prod-1",
            enrichedProductJson("prod-1", "Air Max", "Nike", "http://img/airmax.jpg"))
        paymentsGktInput.pipeInput("pay-1",
            enrichedPaymentJson("pay-1", method = "CREDIT_CARD"))
        deliveriesGktInput.pipeInput("del-1",
            enrichedDeliveryJson("del-1", status = "SHIPPING", trackingNumber = "TRK12345"))

        // When — 주문 CDC 이벤트 주입
        ordersCdcInput.pipeInput(
            debeziumKey("order-1"),
            orderCdcJson(
                id = "order-1", paymentTxId = "pay-1", deliveryId = "del-1",
                items = listOf(mapOf("productId" to "prod-1", "quantity" to 2, "priceAtOrder" to 99000.0))
            )
        )

        val node = mapper.readTree(enrichedOrdersOutput.readValue())

        // Then — 기본 주문 필드
        assertEquals("order-1", node.path("orderId").asText())
        assertEquals("PAYMENT_COMPLETED", node.path("status").asText())
        assertEquals(99000.0, node.path("totalAmount").asDouble(), 0.01)

        // Then — Payment 조인: paymentTxId → paymentMethod
        assertEquals("CREDIT_CARD", node.path("paymentMethod").asText())

        // Then — Delivery 조인: deliveryId → deliveryStatus, trackingNumber
        assertEquals("SHIPPING", node.path("deliveryStatus").asText())
        assertEquals("TRK12345", node.path("trackingNumber").asText())

        // Then — Product 조인: items[].productId → productName, sellerName, imageUrl
        val items = node.path("items")
        assertTrue(items.isArray)
        assertEquals(1, items.size())
        assertEquals("prod-1", items[0].path("productId").asText())
        assertEquals("Air Max", items[0].path("productName").asText())
        assertEquals("Nike", items[0].path("sellerName").asText())
        assertEquals("http://img/airmax.jpg", items[0].path("imageUrl").asText())
        assertEquals(2, items[0].path("quantity").asInt())
        assertEquals(99000.0, items[0].path("price").asDouble(), 0.01)
    }

    @Test
    fun `GlobalKTable이 비어있을 때 조인 실패를 graceful하게 처리한다`() {
        // Given — 상태 저장소 비어있음
        ordersCdcInput.pipeInput(
            debeziumKey("order-2"),
            orderCdcJson(
                id = "order-2", paymentTxId = "pay-unknown", deliveryId = "del-unknown",
                items = listOf(mapOf("productId" to "prod-unknown", "quantity" to 1, "priceAtOrder" to 50000.0))
            )
        )

        val node = mapper.readTree(enrichedOrdersOutput.readValue())

        // Then — 기본 필드는 정상 출력
        assertEquals("order-2", node.path("orderId").asText())

        // Then — 조인 실패 시 해당 필드가 없거나 빈값 (NPE 없이 graceful)
        assertTrue(node.path("paymentMethod").isMissingNode || node.path("paymentMethod").asText().isEmpty())
        assertTrue(node.path("deliveryStatus").isMissingNode || node.path("deliveryStatus").asText().isEmpty())

        // Then — items의 product 필드는 빈 문자열
        val items = node.path("items")
        assertEquals(1, items.size())
        assertEquals("", items[0].path("productName").asText())
        assertEquals("", items[0].path("sellerName").asText())
        assertTrue(items[0].path("imageUrl").isNull)
    }

    @Test
    fun `여러 items가 각각 독립적으로 product enrichment된다`() {
        // Given
        productsGktInput.pipeInput("prod-A",
            enrichedProductJson("prod-A", "Shirt", "BrandA", "http://img/shirt.jpg"))
        productsGktInput.pipeInput("prod-B",
            enrichedProductJson("prod-B", "Pants", "BrandB", "http://img/pants.jpg"))

        ordersCdcInput.pipeInput(
            debeziumKey("order-3"),
            orderCdcJson(
                id = "order-3",
                items = listOf(
                    mapOf("productId" to "prod-A", "quantity" to 1, "priceAtOrder" to 30000.0),
                    mapOf("productId" to "prod-B", "quantity" to 3, "priceAtOrder" to 60000.0)
                )
            )
        )

        val items = mapper.readTree(enrichedOrdersOutput.readValue()).path("items")
        assertEquals(2, items.size())
        assertEquals("Shirt", items[0].path("productName").asText())
        assertEquals("BrandA", items[0].path("sellerName").asText())
        assertEquals("Pants", items[1].path("productName").asText())
        assertEquals("BrandB", items[1].path("sellerName").asText())
    }

    @Test
    fun `paymentTxId 없으면 paymentMethod 필드가 결과에 포함되지 않는다`() {
        // Given — paymentTxId 미설정 주문
        ordersCdcInput.pipeInput(
            debeziumKey("order-4"),
            orderCdcJson(id = "order-4", paymentTxId = null, deliveryId = null)
        )

        val node = mapper.readTree(enrichedOrdersOutput.readValue())
        // paymentTxId가 없으므로 paymentMethod 조인 자체가 시도되지 않음
        assertTrue(node.path("paymentMethod").isMissingNode)
    }

    @Test
    fun `__deleted true이면 tombstone(null value)이 출력된다`() {
        ordersCdcInput.pipeInput(
            debeziumKey("order-del"),
            """{"__deleted":"true","_id":"order-del"}"""
        )

        val record = enrichedOrdersOutput.readRecord()
        assertEquals("order-del", record.key)
        assertNull(record.value)
    }

    // ── Fixture 헬퍼 ──────────────────────────────────────────────────────────

    private fun debeziumKey(id: String) = """{"_id":"$id"}"""

    private fun orderCdcJson(
        id: String,
        paymentTxId: String? = null,
        deliveryId: String? = null,
        items: List<Map<String, Any>> = emptyList()
    ): String {
        val map = mutableMapOf<String, Any?>(
            "_id" to id, "userId" to "user-1", "status" to "PAYMENT_COMPLETED",
            "totalAmount" to 99000.0, "deliveryAddress" to "Seoul Gangnam-gu",
            "receiverName" to "Hong Gildong", "receiverPhone" to "010-1234-5678",
            "items" to items
        )
        if (paymentTxId != null) map["paymentTxId"] = paymentTxId
        if (deliveryId != null) map["deliveryId"] = deliveryId
        return mapper.writeValueAsString(map)
    }

    /** GlobalKTable(enriched-products)에 적재할 이미 enriched된 상품 JSON */
    private fun enrichedProductJson(
        id: String, name: String, sellerName: String, imageUrl: String
    ): String = mapper.writeValueAsString(mapOf(
        "productId" to id, "name" to name, "sellerName" to sellerName, "imageUrl" to imageUrl,
        "salePrice" to 99000.0, "category" to "Fashion", "status" to "ACTIVE"
    ))

    /** GlobalKTable(enriched-payments)에 적재할 이미 enriched된 결제 JSON */
    private fun enrichedPaymentJson(id: String, method: String): String =
        mapper.writeValueAsString(mapOf(
            "paymentId" to id, "orderId" to "order-1", "userId" to "user-1",
            "totalAmount" to 99000.0, "status" to "APPROVED",
            "method" to method, "pgProvider" to "TOSS_PAYMENTS"
        ))

    /** GlobalKTable(enriched-deliveries)에 적재할 이미 enriched된 배송 JSON */
    private fun enrichedDeliveryJson(
        id: String, status: String, trackingNumber: String?
    ): String = mapper.writeValueAsString(mapOf(
        "deliveryId" to id, "orderId" to "order-1", "userId" to "user-1",
        "status" to status, "trackingNumber" to trackingNumber
    ))
}
