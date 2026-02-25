package org.example.paymentservice.saga

import com.google.protobuf.GeneratedMessage
import common.saga.command.PaymentCommand
import common.saga.command.PaymentCommandType
import common.saga.reply.paymentReply
import org.example.paymentservice.service.PaymentService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class PaymentCommandHandler(
    private val paymentService: PaymentService,
    private val kafkaTemplate: KafkaTemplate<String, GeneratedMessage>
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["payment-command"], groupId = "payment-saga")
    suspend fun handle(command: PaymentCommand, ack: Acknowledgment) {
        log.info("[Payment] Command received - sagaId={}, type={}", command.sagaId, command.type)

        val reply = when (command.type) {
            PaymentCommandType.PROCESS                                -> process(command)
            PaymentCommandType.REFUND                                 -> refund(command)
            PaymentCommandType.PAYMENT_COMMAND_TYPE_UNSPECIFIED,
            PaymentCommandType.UNRECOGNIZED                           -> {
                log.warn("[Payment] Unknown command type - sagaId={}", command.sagaId)
                paymentReply {
                    sagaId = command.sagaId
                    success = false
                    failureReason = "Unknown command type: ${command.type}"
                }
            }
        }

        kafkaTemplate.send("payment-reply", command.sagaId, reply)
        ack.acknowledge()
    }

    private suspend fun process(command: PaymentCommand) = try {
        val paymentId = paymentService.processPayment(
            sagaId  = command.sagaId,
            orderId = command.orderId,
            userId  = command.userId,
            amount  = command.amount
        )
        paymentReply {
            sagaId = command.sagaId
            success = true
            this.paymentId = paymentId
        }
    } catch (e: Exception) {
        log.error("[Payment] Process failed - sagaId={}", command.sagaId, e)
        paymentReply {
            sagaId = command.sagaId
            success = false
            failureReason = e.message ?: "결제 처리 실패"
        }
    }

    private suspend fun refund(command: PaymentCommand) = try {
        paymentService.refundPayment(
            sagaId  = command.sagaId,
            orderId = command.orderId,
            amount  = command.amount
        )
        paymentReply {
            sagaId = command.sagaId
            success = true
        }
    } catch (e: Exception) {
        log.error("[Payment] Refund failed - sagaId={}", command.sagaId, e)
        paymentReply {
            sagaId = command.sagaId
            success = false
            failureReason = e.message ?: "환불 처리 실패"
        }
    }
}
