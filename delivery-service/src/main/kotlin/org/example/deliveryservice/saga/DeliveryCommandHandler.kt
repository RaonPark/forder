package org.example.deliveryservice.saga

import com.google.protobuf.GeneratedMessage
import common.saga.command.DeliveryCommand
import common.saga.command.DeliveryCommandType
import common.saga.reply.deliveryReply
import kotlinx.coroutines.future.await
import org.example.deliveryservice.exception.InvalidDeliveryOperationException
import org.example.deliveryservice.service.DeliveryService
import org.example.deliveryservice.service.ReturnDeliveryService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class DeliveryCommandHandler(
    private val deliveryService: DeliveryService,
    private val returnDeliveryService: ReturnDeliveryService,
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
            DeliveryCommandType.RETURN_PICKUP                           -> returnPickup(command)
        }

        kafkaTemplate.send("delivery-reply", command.sagaId, reply).await()
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
    } catch (e: InvalidDeliveryOperationException) {
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
    } catch (e: InvalidDeliveryOperationException) {
        log.error("[Delivery] Cancel failed - sagaId={}", command.sagaId, e)
        deliveryReply {
            sagaId = command.sagaId
            success = false
            failureReason = e.message ?: "배송 취소 실패"
        }
    }

    private suspend fun returnPickup(command: DeliveryCommand) = try {
        val returnDeliveryId = returnDeliveryService.scheduleReturnPickupFromSaga(
            sagaId        = command.sagaId,
            orderId       = command.orderId,
            pickupAddress = command.deliveryAddress,
            receiverName  = command.receiverName,
            receiverPhone = command.receiverPhone
        )
        deliveryReply {
            sagaId = command.sagaId
            success = true
            deliveryId = returnDeliveryId
        }
    } catch (e: InvalidDeliveryOperationException) {
        log.error("[Delivery] ReturnPickup failed - sagaId={}", command.sagaId, e)
        deliveryReply {
            sagaId = command.sagaId
            success = false
            failureReason = e.message ?: "반품 픽업 스케줄 실패"
        }
    }
}
