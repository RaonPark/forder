package org.example.orderservice.config

import com.google.protobuf.GeneratedMessage
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer
import io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializerConfig
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.BackOffExecution
import org.springframework.util.backoff.ExponentialBackOff
import java.util.concurrent.ThreadLocalRandom

@Configuration
class KafkaConfig(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
    @Value("\${spring.kafka.properties.schema.registry.url}") private val schemaRegistryUrl: String
) {

    // ──────────────────────────────────────────
    // Producer
    // ──────────────────────────────────────────

    @Bean
    fun producerFactory(): ProducerFactory<String, GeneratedMessage> {
        val configs = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to KafkaProtobufSerializer::class.java.name,
            AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl,
            // 정확히 한 번 전송 (Idempotent Producer)
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION to 5,
            ProducerConfig.RETRIES_CONFIG to Int.MAX_VALUE,
        )
        return DefaultKafkaProducerFactory(configs)
    }

    @Bean
    fun kafkaTemplate(): KafkaTemplate<String, GeneratedMessage> =
        KafkaTemplate(producerFactory())

    // ──────────────────────────────────────────
    // Consumer
    // ──────────────────────────────────────────

    @Bean
    fun consumerFactory(): ConsumerFactory<String, GeneratedMessage> {
        val configs = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to KafkaProtobufDeserializer::class.java.name,
            AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl,
            // 스키마 레지스트리에서 타입을 자동으로 추론 (GeneratedMessage 서브타입으로 역직렬화)
            KafkaProtobufDeserializerConfig.DERIVE_TYPE_CONFIG to true,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        )
        return DefaultKafkaConsumerFactory(configs)
    }

    @Bean
    fun kafkaListenerContainerFactory(
        kafkaTemplate: KafkaTemplate<String, GeneratedMessage>
    ): ConcurrentKafkaListenerContainerFactory<String, GeneratedMessage> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, GeneratedMessage>()
        factory.setConsumerFactory(consumerFactory())

        // 수동 ACK: 비즈니스 로직 완료 후 커밋
        factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
        factory.setConcurrency(3)

        // DLT: 최대 2분 동안 지수 백오프 + jitter 재시도, 이후 {topic}.DLT 토픽으로 발행
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate)
        val errorHandler = DefaultErrorHandler(recoverer, JitterExponentialBackOff())
        factory.setCommonErrorHandler(errorHandler)

        return factory
    }
}

/**
 * ExponentialBackOff에 ±jitterRange 랜덤 지연을 추가한 BackOff 구현.
 *
 * 재시도 간격: 1s → 2s → 4s → 8s → ... → 최대 30s (각 구간에 ±500ms jitter)
 * 총 재시도 시간: 최대 2분 초과 시 DLT로 라우팅
 *
 * Jitter 목적: 여러 Consumer가 동시에 OptimisticLockingException을 만날 때
 * 동일한 시점에 재시도하여 다시 충돌하는 thundering herd 방지.
 */
private class JitterExponentialBackOff(
    initialInterval: Long = 1_000L,
    multiplier: Double = 2.0,
    maxInterval: Long = 30_000L,
    maxElapsedTime: Long = 120_000L,
    private val jitterRange: Long = 500L
) : BackOff {

    private val delegate = ExponentialBackOff(initialInterval, multiplier).apply {
        this.maxInterval = maxInterval
        this.maxElapsedTime = maxElapsedTime
    }

    override fun start(): BackOffExecution {
        val delegateExecution = delegate.start()
        return object : BackOffExecution {
            override fun nextBackOff(): Long {
                val next = delegateExecution.nextBackOff()
                return if (next == BackOffExecution.STOP) next
                else next + ThreadLocalRandom.current().nextLong(0, jitterRange)
            }
        }
    }
}
