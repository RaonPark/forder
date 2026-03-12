package org.example.kafkastreamsapp.topology

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.utils.Bytes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Materialized
import org.apache.kafka.streams.kstream.Named
import org.apache.kafka.streams.kstream.Produced
import org.apache.kafka.streams.processor.api.FixedKeyProcessorSupplier
import org.apache.kafka.streams.state.KeyValueStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Debezium CDC → Kafka Streams → Elasticsearch enrichment topology.
 *
 * 입력 토픽 (Debezium MongoDB CDC, JsonConverter schemas.enable=false):
 *   key:   {"id": "<entity-id>"}  (ExtractNewDocumentState SMT가 _id → id 변환)
 *   value: flattened document JSON (with __deleted: "true" on delete)
 *
 * 출력 토픽 (ES Sink, plain JSON):
 *   key:   "<entity-id>"  (plain string, ES document _id)
 *   value: enriched JSON (null = tombstone → ES delete)
 *
 * GlobalKTable 의존 관계:
 *   products-store  ← enriched-products  (inventory, order item 조회)
 *   payments-store  ← enriched-payments  (order paymentMethod 조회)
 *   deliveries-store← enriched-deliveries(order trackingNumber/status 조회)
 *
 * 주의: enriched-* 토픽은 같은 앱에서 쓰고(sink) GlobalKTable에서 읽는다(source).
 * Kafka Streams은 regular sink ≠ regular source 이므로 GlobalKTable 등록 시
 * topology 충돌 없이 허용된다.
 */
@Configuration
class EnrichmentTopology(private val mapper: ObjectMapper) {

    companion object {
        // CDC source topics (Debezium output)
        const val ORDERS_CDC = "orders-cdc.forder.orders"
        const val PAYMENTS_CDC = "payments-cdc.forder.payments"
        const val DELIVERIES_CDC = "deliveries-cdc.forder.deliveries"
        const val INVENTORY_CDC = "inventory-cdc.forder.inventory"
        const val PRODUCTS_CDC = "products-cdc.forder.products"

        // Enriched output topics (→ Elasticsearch)
        const val ENRICHED_ORDERS = "enriched-orders"
        const val ENRICHED_PAYMENTS = "enriched-payments"
        const val ENRICHED_DELIVERIES = "enriched-deliveries"
        const val ENRICHED_INVENTORY = "enriched-inventory"
        const val ENRICHED_PRODUCTS = "enriched-products"

        // GlobalKTable store names (sourced from enriched-* output topics)
        const val PRODUCTS_STORE = "products-store"
        const val PAYMENTS_STORE = "payments-store"
        const val DELIVERIES_STORE = "deliveries-store"
    }

    @Bean
    fun buildTopology(streamsBuilder: StreamsBuilder) {
        // ── GlobalKTables ─────────────────────────────────────────────────────
        // enriched-* 출력 토픽을 GlobalKTable 소스로 사용.
        // key: plain string entity id, value: enriched JSON
        registerGlobalTable(streamsBuilder, ENRICHED_PRODUCTS, PRODUCTS_STORE)
        registerGlobalTable(streamsBuilder, ENRICHED_PAYMENTS, PAYMENTS_STORE)
        registerGlobalTable(streamsBuilder, ENRICHED_DELIVERIES, DELIVERIES_STORE)

        // ── Processing topologies ─────────────────────────────────────────────
        buildProductsTopology(streamsBuilder)
        buildPaymentsTopology(streamsBuilder)
        buildDeliveriesTopology(streamsBuilder)
        buildInventoryTopology(streamsBuilder)
        buildOrdersTopology(streamsBuilder)
    }

    // ─── 공통 유틸 ──────────────────────────────────────────────────────────

    private fun registerGlobalTable(builder: StreamsBuilder, topic: String, storeName: String) {
        builder.globalTable(
            topic,
            Consumed.with(Serdes.String(), Serdes.String()),
            Materialized.`as`<String, String, KeyValueStore<Bytes, ByteArray>>(storeName)
                .withKeySerde(Serdes.String())
                .withValueSerde(Serdes.String())
        )
    }

    /**
     * Debezium key → plain entity id string.
     *
     * ExtractNewDocumentState SMT + JsonConverter(schemas.enable=false):
     *   key = {"id":"entity-id"}  (SMT가 _id → id로 변환)
     *
     * SMT 미적용 시:
     *   key = {"_id":"entity-id"} (MongoDB 원본 필드명)
     */
    private fun extractId(keyJson: String): String =
        try {
            val node = mapper.readTree(keyJson)
            val id = node.path("id").asText()
            if (id.isNotEmpty()) id else node.path("_id").asText().ifEmpty { keyJson }
        } catch (e: Exception) { keyJson }

    private fun isDeleted(value: String?): Boolean {
        if (value == null) return true
        return try { mapper.readTree(value).path("__deleted").asText() == "true" } catch (e: Exception) { false }
    }

    // ─── 1. Products ─────────────────────────────────────────────────────────

    private fun buildProductsTopology(builder: StreamsBuilder) {
        builder.stream<String, String>(PRODUCTS_CDC, Consumed.with(Serdes.String(), Serdes.String()))
            .map { keyJson, value ->
                val id = extractId(keyJson)
                org.apache.kafka.streams.KeyValue(id, if (isDeleted(value)) null else enrichProduct(value!!))
            }
            .to(ENRICHED_PRODUCTS, produced())
    }

    private fun enrichProduct(value: String): String {
        val node = mapper.readTree(value) as ObjectNode
        val name = node.path("name").asText()
        val brand = node.path("brand").asText()
        val category = node.path("category").asText()
        val tagsNode = node.path("tags")
        val tagsList = if (tagsNode.isArray) tagsNode.map { it.asText() } else emptyList()
        val salePrice = if (!node.path("salePrice").isMissingNode && !node.path("salePrice").isNull)
            node.path("salePrice").asDouble()
        else
            node.path("basePrice").asDouble(0.0)

        return mapper.writeValueAsString(mapper.createObjectNode().apply {
            put("productId", node.path("_id").asText())
            put("name", name)
            put("salePrice", salePrice)
            put("imageUrl", node.path("imageUrl").asText())
            put("category", category)
            put("status", node.path("status").asText())
            set<ObjectNode>("tags", tagsNode.deepCopy())
            put("sellerName", node.path("sellerId").asText())  // seller 서비스 없어 sellerId 사용
            put("averageRating", 0.0)
            put("reviewCount", 0)
            put("currentSalesCount", 0)
            put("fullTextSearch", "$name $brand $category ${tagsList.joinToString(" ")}")
            put("priceRangeBucket", when {
                salePrice < 50_000 -> "LOW"
                salePrice < 500_000 -> "MID"
                else -> "HIGH"
            })
        })
    }

    // ─── 2. Payments ─────────────────────────────────────────────────────────

    private fun buildPaymentsTopology(builder: StreamsBuilder) {
        builder.stream<String, String>(PAYMENTS_CDC, Consumed.with(Serdes.String(), Serdes.String()))
            .map { keyJson, value ->
                val id = extractId(keyJson)
                org.apache.kafka.streams.KeyValue(id, if (isDeleted(value)) null else enrichPayment(value!!))
            }
            .to(ENRICHED_PAYMENTS, produced())
    }

    private fun enrichPayment(value: String): String {
        val node = mapper.readTree(value) as ObjectNode
        return mapper.writeValueAsString(mapper.createObjectNode().apply {
            put("paymentId", node.path("_id").asText())
            put("orderId", node.path("orderId").asText())
            put("userId", node.path("userId").asText())
            put("totalAmount", node.path("totalAmount").asDouble())
            put("canceledAmount", node.path("canceledAmount").asDouble(0.0))
            put("status", node.path("status").asText())
            put("method", node.path("method").asText())
            copyOptional(node, this, "pgTid")
            put("pgProvider", node.path("pgProvider").asText())
            copyOptional(node, this, "createdAt")
            copyOptional(node, this, "updatedAt")
            copyOptional(node, this, "canceledAt")
        })
    }

    // ─── 3. Deliveries ───────────────────────────────────────────────────────

    private fun buildDeliveriesTopology(builder: StreamsBuilder) {
        builder.stream<String, String>(DELIVERIES_CDC, Consumed.with(Serdes.String(), Serdes.String()))
            .map { keyJson, value ->
                val id = extractId(keyJson)
                org.apache.kafka.streams.KeyValue(id, if (isDeleted(value)) null else enrichDelivery(value!!))
            }
            .to(ENRICHED_DELIVERIES, produced())
    }

    private fun enrichDelivery(value: String): String {
        val node = mapper.readTree(value) as ObjectNode
        val destination = node.path("destination")

        val enrichedItems = mapper.createArrayNode()
        val items = node.path("items")
        if (items.isArray) {
            for (item in items) {
                enrichedItems.addObject().apply {
                    put("productId", item.path("productId").asText())
                    put("productName", item.path("productName").asText())
                    put("quantity", item.path("quantity").asInt())
                    putNull("imageUrl")  // DeliveryItemSnapshot에 imageUrl 없음
                }
            }
        }

        return mapper.writeValueAsString(mapper.createObjectNode().apply {
            put("deliveryId", node.path("_id").asText())
            put("orderId", node.path("orderId").asText())
            put("userId", node.path("userId").asText())
            copyOptional(node, this, "trackingNumber")
            put("recipientName", destination.path("receiverName").asText())
            set<ObjectNode>("items", enrichedItems)
            put("status", node.path("status").asText())
            copyOptional(node, this, "courierId")
            putNull("courierName")       // Courier 조회 불가 (별도 서비스)
            putNull("currentLocation")
            putNull("fullTrackingUrl")
            putNull("lastDescription")
            copyOptional(node, this, "startedAt")
            putNull("estimatedArrival")
            copyFieldAs(node, this, "completedAt", "deliveredAt")
        })
    }

    // ─── 4. Inventory + Products GlobalKTable ────────────────────────────────

    private fun buildInventoryTopology(builder: StreamsBuilder) {
        builder.stream<String, String>(INVENTORY_CDC, Consumed.with(Serdes.String(), Serdes.String()))
            .map { keyJson, value -> org.apache.kafka.streams.KeyValue(extractId(keyJson), value) }
            .processValues(
                FixedKeyProcessorSupplier { InventoryEnricher(mapper) },
                Named.`as`("inventory-enricher")
                // GlobalKTable store는 모든 Processor에 자동 공개되므로 명시 불필요
            )
            .to(ENRICHED_INVENTORY, produced())
    }

    // ─── 5. Orders + Payments/Deliveries/Products GlobalKTables ─────────────

    private fun buildOrdersTopology(builder: StreamsBuilder) {
        builder.stream<String, String>(ORDERS_CDC, Consumed.with(Serdes.String(), Serdes.String()))
            .map { keyJson, value -> org.apache.kafka.streams.KeyValue(extractId(keyJson), value) }
            .processValues(
                FixedKeyProcessorSupplier { OrderEnricher(mapper) },
                Named.`as`("order-enricher")
                // GlobalKTable store는 모든 Processor에 자동 공개되므로 명시 불필요
            )
            .to(ENRICHED_ORDERS, produced())
    }

    // ─── 유틸 ────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun produced() = Produced.with(Serdes.String(), Serdes.String())
            as Produced<String, String?>

    private fun copyOptional(from: ObjectNode, to: ObjectNode, field: String) {
        val node = from.path(field)
        if (!node.isMissingNode && !node.isNull) to.set(field, node)
        else to.putNull(field)
    }

    private fun copyFieldAs(from: ObjectNode, to: ObjectNode, fromField: String, toField: String) {
        val node = from.path(fromField)
        if (!node.isMissingNode && !node.isNull) to.set(toField, node)
        else to.putNull(toField)
    }
}
