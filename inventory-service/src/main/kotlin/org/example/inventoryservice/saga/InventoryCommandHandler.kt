package org.example.inventoryservice.saga

import com.google.protobuf.GeneratedMessageV3
import common.saga.command.InventoryCommand
import common.saga.command.InventoryCommandType
import common.saga.reply.inventoryReply
import org.example.inventoryservice.service.InventoryService
import org.example.inventoryservice.service.InventoryService.SagaItem
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class InventoryCommandHandler(
    private val inventoryService: InventoryService,
    private val kafkaTemplate: KafkaTemplate<String, GeneratedMessageV3>
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["inventory-command"], groupId = "inventory-saga")
    suspend fun handle(command: InventoryCommand, ack: Acknowledgment) {
        log.info("[Inventory] Command received - sagaId={}, type={}", command.sagaId, command.type)

        val reply = when (command.type) {
            InventoryCommandType.RESERVE  -> reserve(command)
            InventoryCommandType.RELEASE  -> release(command)
            InventoryCommandType.INVENTORY_COMMAND_TYPE_UNSPECIFIED,
            InventoryCommandType.UNRECOGNIZED -> {
                log.warn("[Inventory] Unknown command type - sagaId={}", command.sagaId)
                inventoryReply {
                    sagaId = command.sagaId
                    success = false
                    failureReason = "Unknown command type: ${command.type}"
                }
            }
        }

        kafkaTemplate.send("inventory-reply", command.sagaId, reply)
        ack.acknowledge()
    }

    private suspend fun reserve(command: InventoryCommand) = try {
        val items = command.itemsList.map { SagaItem(it.productId, it.optionId.ifEmpty { null }, it.quantity) }
        val success = inventoryService.reserveStock(command.sagaId, items)
        inventoryReply {
            sagaId = command.sagaId
            this.success = success
            if (!success) failureReason = "재고 부족"
        }
    } catch (e: Exception) {
        log.error("[Inventory] Reserve failed - sagaId={}", command.sagaId, e)
        inventoryReply {
            sagaId = command.sagaId
            success = false
            failureReason = e.message ?: "재고 예약 실패"
        }
    }

    private suspend fun release(command: InventoryCommand) = try {
        val items = command.itemsList.map { SagaItem(it.productId, it.optionId.ifEmpty { null }, it.quantity) }
        inventoryService.releaseStock(command.sagaId, items)
        inventoryReply {
            sagaId = command.sagaId
            success = true
        }
    } catch (e: Exception) {
        log.error("[Inventory] Release failed - sagaId={}", command.sagaId, e)
        inventoryReply {
            sagaId = command.sagaId
            success = false
            failureReason = e.message ?: "재고 해제 실패"
        }
    }
}
