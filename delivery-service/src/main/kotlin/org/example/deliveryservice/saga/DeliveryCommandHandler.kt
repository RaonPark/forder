package org.example.deliveryservice.saga

import com.google.protobuf.GeneratedMessage
import common.saga.command.DeliveryCommand
import common.saga.command.DeliveryCommandType
import common.saga.reply.deliveryReply
import org.example.deliveryservice.service.DeliveryService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class DeliveryCommandHandler(
    private val deliveryService: DeliveryService,
    private val kafkaTemplate: KafkaTemplate<String, GeneratedMessage>
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["delivery-command"], groupId = "delivery-saga")
    suspend fun handle(command: DeliveryCommand, ack: Acknowledgment) {
        log.info("[Delivery] Command received - sagaId={}, type={}", command.sagaId, command.type)

        val reply = when (command.type) {
            DeliveryCommandType.CREATE                                  -> create(command)
            DeliveryCommandType.CANCEL                                  -> cancel(command)
            DeliveryCommandType.DELIVERY_COMMAND_TYPE_UNSPECIFIED,
            DeliveryCommandType.UNRECOGNIZED                            -> {
                log.warn("[Delivery] Unknown command type - sagaId={}", command.sagaId)
                deliveryReply {
                    sagaId = command.sagaId
                    success = false
                    failureReason = "Unknown command type: ${command.type}"
                }
            }
        }

        kafkaTemplate.send("delivery-reply", command.sagaId, reply)
        ack.acknowledge()
    }

    private suspend fun create(command: DeliveryCommand) = try {
        val deliveryId = deliveryService.createDeliveryFromSaga(
            sagaId          = command.sagaId,
            orderId         = command.orderId,
            userId          = command.userId,
            receiverName    = command.receiverName,
            receiverPhone   = command.receiverPhone,
            deliveryAddress = command.deliveryAddress
        )
        deliveryReply {
            sagaId = command.sagaId
            success = true
            this.deliveryId = deliveryId
        }
    } catch (e: Exception) {
        log.error("[Delivery] Create failed - sagaId={}", command.sagaId, e)
        deliveryReply {
            sagaId = command.sagaId
            success = false
            failureReason = e.message ?: "배송 생성 실패"
        }
    }

    private suspend fun cancel(command: DeliveryCommand) = try {
        deliveryService.cancelDeliveryFromSaga(command.orderId)
        deliveryReply {
            sagaId = command.sagaId
            success = true
        }
    } catch (e: Exception) {
        log.error("[Delivery] Cancel failed - sagaId={}", command.sagaId, e)
        deliveryReply {
            sagaId = command.sagaId
            success = false
            failureReason = e.message ?: "배송 취소 실패"
        }
    }
}
