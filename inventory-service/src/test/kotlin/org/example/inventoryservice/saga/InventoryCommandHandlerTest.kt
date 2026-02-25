package org.example.inventoryservice.saga

import com.google.protobuf.GeneratedMessage
import common.saga.command.InventoryCommand
import common.saga.command.InventoryCommandItem
import common.saga.command.InventoryCommandType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.example.inventoryservice.service.InventoryService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import common.saga.reply.InventoryReply
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

@ExtendWith(MockKExtension::class)
class InventoryCommandHandlerTest {

    @MockK
    lateinit var inventoryService: InventoryService

    @MockK
    lateinit var kafkaTemplate: KafkaTemplate<String, GeneratedMessage>

    @InjectMockKs
    lateinit var handler: InventoryCommandHandler

    private lateinit var ack: Acknowledgment

    @BeforeEach
    fun setUp() {
        ack = mockk(relaxed = true)
        every { kafkaTemplate.send(any<String>(), any<String>(), any()) } returns mockk(relaxed = true)
    }

    private fun mockCommand(
        sagaId: String,
        type: InventoryCommandType,
        productId: String = "prod-1",
        optionId: String = "opt-1",
        quantity: Int = 10,
    ): InventoryCommand {
        val item = mockk<InventoryCommandItem> {
            every { this@mockk.productId } returns productId
            every { this@mockk.optionId } returns optionId
            every { this@mockk.quantity } returns quantity
        }
        return mockk {
            every { this@mockk.sagaId } returns sagaId
            every { this@mockk.type } returns type
            every { itemsList } returns listOf(item)
        }
    }

    // ──────────────────────────────────────────
    // RESERVE 커맨드
    // ──────────────────────────────────────────

    @Nested
    inner class ReserveCommand {

        @Test
        fun `RESERVE 커맨드 성공 시 success=true reply 발행`() = runTest {
            val command = mockCommand("saga-001", InventoryCommandType.RESERVE)
            coEvery { inventoryService.reserveStock(any(), any()) } returns true

            handler.handle(command, ack)

            val replySlot = slot<GeneratedMessage>()
            verify { kafkaTemplate.send("inventory-reply", "saga-001", capture(replySlot)) }

            val reply = replySlot.captured
            if (reply is InventoryReply) {
                assertTrue(reply.success)
                assertEquals("saga-001", reply.sagaId)
            } else {
                fail("발행된 메세지가 InventoryReply 타입이 아닙니다.")
            }
        }

        @Test
        fun `RESERVE 커맨드 재고 부족 시 success=false reply 발행`() = runTest {
            val command = mockCommand("saga-002", InventoryCommandType.RESERVE)
            coEvery { inventoryService.reserveStock(any(), any()) } returns false

            handler.handle(command, ack)

            val replySlot = slot<GeneratedMessage>()
            verify { kafkaTemplate.send("inventory-reply", "saga-002", capture(replySlot)) }

            val reply = replySlot.captured as InventoryReply
            assertFalse(reply.success)
            assertTrue(reply.failureReason.isNotBlank())
        }
    }

    // ──────────────────────────────────────────
    // RELEASE 커맨드
    // ──────────────────────────────────────────

    @Nested
    inner class ReleaseCommand {

        @Test
        fun `RELEASE 커맨드 성공 시 success=true reply 발행`() = runTest {
            val command = mockCommand("saga-003", InventoryCommandType.RELEASE)
            coEvery { inventoryService.releaseStock(any(), any()) } just runs

            handler.handle(command, ack)

            val replySlot = slot<GeneratedMessage>()
            verify { kafkaTemplate.send("inventory-reply", "saga-003", capture(replySlot)) }

            val reply = replySlot.captured as InventoryReply
            assertTrue(reply.success)
        }
    }

    // ──────────────────────────────────────────
    // 알 수 없는 커맨드 타입
    // ──────────────────────────────────────────

    @Nested
    inner class UnknownCommand {

        @Test
        fun `UNSPECIFIED 타입 수신 시 success=false reply 발행`() = runTest {
            val command = mockCommand("saga-004", InventoryCommandType.INVENTORY_COMMAND_TYPE_UNSPECIFIED)

            handler.handle(command, ack)

            val replySlot = slot<GeneratedMessage>()
            verify { kafkaTemplate.send("inventory-reply", "saga-004", capture(replySlot)) }

            val reply = replySlot.captured as InventoryReply
            assertFalse(reply.success)
            assertTrue(reply.failureReason.isNotBlank())
        }
    }

    // ──────────────────────────────────────────
    // Acknowledgment
    // ──────────────────────────────────────────

    @Test
    fun `처리 완료 후 반드시 ack 호출`() = runTest {
        val command = mockCommand("saga-005", InventoryCommandType.RESERVE)
        coEvery { inventoryService.reserveStock(any(), any()) } returns true

        handler.handle(command, ack)

        verify(exactly = 1) { ack.acknowledge() }
    }
}
