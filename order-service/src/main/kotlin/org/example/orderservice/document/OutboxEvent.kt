package org.example.orderservice.document

import com.google.protobuf.GeneratedMessage
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant
import java.util.UUID

@Document(collection = "outbox")
data class OutboxEvent(
    @Id val eventId: String,
    val aggregateId: String,   // sagaId (= orderId)
    val topic: String,         // 발행할 Kafka 토픽
    val payload: ByteArray,    // proto 직렬화된 커맨드 바이트
    val createdAt: Instant = Instant.now()
) {
    companion object {
        fun of(aggregateId: String, topic: String, message: GeneratedMessage) = OutboxEvent(
            eventId = UUID.randomUUID().toString(),
            aggregateId = aggregateId,
            topic = topic,
            payload = message.toByteArray()
        )
    }
}
