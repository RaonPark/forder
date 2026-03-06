package org.example.kafkastreamsapp.topology

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.kafka.streams.processor.api.FixedKeyProcessor
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext
import org.apache.kafka.streams.processor.api.FixedKeyRecord
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore

class InventoryEnricher(
    private val mapper: ObjectMapper
) : FixedKeyProcessor<String, String, String?> {

    private lateinit var context: FixedKeyProcessorContext<String, String?>
    private var productsStore: ReadOnlyKeyValueStore<String, String>? = null

    override fun init(context: FixedKeyProcessorContext<String, String?>) {
        this.context = context
        this.productsStore = context.getStateStore(EnrichmentTopology.PRODUCTS_STORE)
    }

    override fun process(record: FixedKeyRecord<String, String>) {
        val value = record.value()
        if (value == null || isDeleted(value)) {
            context.forward(record.withValue(null))
            return
        }

        val node = mapper.readTree(value) as ObjectNode
        val productId = node.path("productId").asText()
        val productJson = productsStore?.get(productId)
        val product = productJson?.let { mapper.readTree(it) }

        val currentStock = node.path("currentStock").asInt()
        val reservedStock = node.path("reservedStock").asInt()
        val safetyStock = node.path("safetyStock").asInt()
        val stockStatus = when {
            currentStock == 0 -> "SOLD_OUT"
            (currentStock - reservedStock) <= safetyStock -> "LOW"
            else -> "NORMAL"
        }

        val enriched = mapper.createObjectNode().apply {
            put("inventoryId", node.path("_id").asText())
            put("productId", productId)
            putOrNull("optionId", node.path("optionId").takeIf { !it.isMissingNode && !it.isNull }?.asText())
            put("currentStock", currentStock)
            put("reservedStock", reservedStock)
            put("safetyStock", safetyStock)
            put("productName", product?.path("name")?.asText() ?: "")
            put("category", product?.path("category")?.asText() ?: "")
            putOrNull("imageUrl", product?.path("imageUrl")?.takeIf { !it.isMissingNode && !it.isNull }?.asText())
            put("stockStatus", stockStatus)
            put("location", node.path("location").asText())
        }

        context.forward(record.withValue(mapper.writeValueAsString(enriched)))
    }

    private fun isDeleted(value: String): Boolean =
        try { mapper.readTree(value).path("__deleted").asText() == "true" } catch (e: Exception) { false }

    private fun ObjectNode.putOrNull(field: String, value: String?) {
        if (value != null) put(field, value) else putNull(field)
    }
}
