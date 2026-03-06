package org.example.kafkastreamsapp.topology

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.kafka.streams.processor.api.FixedKeyProcessor
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext
import org.apache.kafka.streams.processor.api.FixedKeyRecord
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore

class OrderEnricher(
    private val mapper: ObjectMapper
) : FixedKeyProcessor<String, String, String?> {

    private lateinit var context: FixedKeyProcessorContext<String, String?>
    private var productsStore: ReadOnlyKeyValueStore<String, String>? = null
    private var paymentsStore: ReadOnlyKeyValueStore<String, String>? = null
    private var deliveriesStore: ReadOnlyKeyValueStore<String, String>? = null

    override fun init(context: FixedKeyProcessorContext<String, String?>) {
        this.context = context
        productsStore = context.getStateStore(EnrichmentTopology.PRODUCTS_STORE)
        paymentsStore = context.getStateStore(EnrichmentTopology.PAYMENTS_STORE)
        deliveriesStore = context.getStateStore(EnrichmentTopology.DELIVERIES_STORE)
    }

    override fun process(record: FixedKeyRecord<String, String>) {
        val value = record.value()
        if (value == null || isDeleted(value)) {
            context.forward(record.withValue(null))
            return
        }

        val order = mapper.readTree(value) as ObjectNode
        val enriched = enrich(order)
        context.forward(record.withValue(mapper.writeValueAsString(enriched)))
    }

    private fun enrich(order: ObjectNode): ObjectNode {
        val result = mapper.createObjectNode()

        result.put("orderId", order.path("_id").asText())
        result.put("userId", order.path("userId").asText())
        result.put("status", order.path("status").asText())
        result.put("totalAmount", order.path("totalAmount").asDouble())
        result.put("deliveryAddress", order.path("deliveryAddress").asText())
        result.put("receiverName", order.path("receiverName").asText())
        result.put("receiverPhone", order.path("receiverPhone").asText())
        result.copyIfPresent(order, "paymentTxId")
        result.copyIfPresent(order, "cancelledAt")
        result.copyIfPresent(order, "cancelledReason")
        result.copyIfPresent(order, "cancellationStatus")
        result.copyIfPresent(order, "returnedAt")
        result.copyField(order, "returnReason", "returnedReason")
        result.copyIfPresent(order, "returnStatus")
        result.copyIfPresent(order, "createdAt")
        result.copyIfPresent(order, "updatedAt")

        // Payment enrichment: paymentTxId → paymentMethod
        val paymentTxId = order.path("paymentTxId").asText()
        if (paymentTxId.isNotEmpty()) {
            paymentsStore?.get(paymentTxId)?.let { paymentJson ->
                val payment = mapper.readTree(paymentJson)
                result.put("paymentMethod", payment.path("method").asText())
            }
        }

        // Delivery enrichment: deliveryId → trackingNumber, deliveryStatus, actualDeliveryDate
        val deliveryId = order.path("deliveryId").asText()
        if (deliveryId.isNotEmpty()) {
            deliveriesStore?.get(deliveryId)?.let { deliveryJson ->
                val delivery = mapper.readTree(deliveryJson)
                result.copyIfPresent(delivery as ObjectNode, "trackingNumber")
                result.put("deliveryStatus", delivery.path("status").asText())
                result.copyField(delivery, "deliveredAt", "actualDeliveryDate")
            }
        }

        // Items enrichment: productId → productName, sellerName, imageUrl
        result.set<ArrayNode>("items", enrichItems(order.path("items")))

        return result
    }

    private fun enrichItems(items: JsonNode): ArrayNode {
        val result = mapper.createArrayNode()
        if (!items.isArray) return result

        for (item in items) {
            val productId = item.path("productId").asText()
            val product = productsStore?.get(productId)?.let { mapper.readTree(it) }

            result.addObject().apply {
                put("productId", productId)
                put("quantity", item.path("quantity").asInt())
                put("price", item.path("priceAtOrder").asDouble())
                put("productName", product?.path("name")?.asText() ?: "")
                put("sellerName", product?.path("sellerName")?.asText() ?: "")
                val imageUrl = product?.path("imageUrl")
                if (imageUrl != null && !imageUrl.isMissingNode && !imageUrl.isNull) {
                    put("imageUrl", imageUrl.asText())
                } else {
                    putNull("imageUrl")
                }
            }
        }
        return result
    }

    private fun isDeleted(value: String): Boolean =
        try { mapper.readTree(value).path("__deleted").asText() == "true" } catch (e: Exception) { false }

    private fun ObjectNode.copyIfPresent(from: ObjectNode, field: String) {
        val node = from.path(field)
        if (!node.isMissingNode && !node.isNull) set<JsonNode>(field, node)
    }

    private fun ObjectNode.copyField(from: ObjectNode, fromField: String, toField: String) {
        val node = from.path(fromField)
        if (!node.isMissingNode && !node.isNull) set<JsonNode>(toField, node)
    }
}
