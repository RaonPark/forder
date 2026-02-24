package org.example.deliveryservice.saga

import common.saga.command.DeliveryCommand
import common.saga.command.DeliveryCommandType
import common.saga.reply.DeliveryReply
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class DeliveryCommandHandler(
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["delivery-command"], groupId = "delivery-saga")
    suspend fun handle(command: DeliveryCommand) {
        log.info("[Delivery] Command received - sagaId={}, type={}", command.sagaId, command.type)

        val reply = when (command.type) {
            DeliveryCommandType.CREATE -> create(command)
            DeliveryCommandType.CANCEL -> cancel(command)
        }

        kafkaTemplate.send("delivery-reply", command.sagaId, reply)
    }

    private suspend fun create(command: DeliveryCommand): DeliveryReply {
        return try {
            // TODO: 실제 배송 생성 로직 구현
            // val delivery = deliveryService.createDelivery(command)
            val deliveryId = "dlv-${command.sagaId}"
            DeliveryReply(sagaId = command.sagaId, success = true, deliveryId = deliveryId)
        } catch (e: Exception) {
            log.error("[Delivery] Create failed - sagaId={}", command.sagaId, e)
            DeliveryReply(sagaId = command.sagaId, success = false, failureReason = e.message)
        }
    }

    private suspend fun cancel(command: DeliveryCommand): DeliveryReply {
        return try {
            // TODO: 실제 배송 취소 로직 구현
            // deliveryService.cancelDelivery(command.orderId)
            DeliveryReply(sagaId = command.sagaId, success = true)
        } catch (e: Exception) {
            log.error("[Delivery] Cancel failed - sagaId={}", command.sagaId, e)
            DeliveryReply(sagaId = command.sagaId, success = false, failureReason = e.message)
        }
    }
}
