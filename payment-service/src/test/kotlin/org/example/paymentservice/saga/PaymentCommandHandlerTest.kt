package org.example.paymentservice.saga

import com.google.protobuf.GeneratedMessage
import common.saga.command.PaymentCommandType
import common.saga.command.paymentCommand
import common.saga.reply.PaymentReply
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.example.paymentservice.service.PaymentService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockKExtension::class)
class PaymentCommandHandlerTest {

    @MockK lateinit var paymentService: PaymentService
    @MockK lateinit var kafkaTemplate: KafkaTemplate<String, GeneratedMessage>

    private lateinit var handler: PaymentCommandHandler
    private lateinit var ack: Acknowledgment

    @BeforeEach
    fun setUp() {
        handler = PaymentCommandHandler(paymentService, kafkaTemplate)
        ack = mockk(relaxed = true)
        every { kafkaTemplate.send(any(), any(), any<GeneratedMessage>()) } returns mockk(relaxed = true)
    }

    // ──────────────────────────────────────────
    // PROCESS 커맨드
    // ──────────────────────────────────────────

    @Nested
    inner class Process {

        @Test
        fun `결제 처리 성공 시 success=true + paymentId 포함 reply 발행`() = runTest {
            coEvery {
                paymentService.processPayment("saga-1", "order-1", "user-1", "10000")
            } returns "pay-new"

            handler.handle(processCommand(), ack)

            val reply = captureReply()
            assertTrue(reply.success)
            assertEquals("saga-1", reply.sagaId)
            assertEquals("pay-new", reply.paymentId)
            assertTrue(reply.failureReason.isEmpty())
        }

        @Test
        fun `결제 처리 중 예외 발생 시 success=false + failureReason 포함 reply 발행`() = runTest {
            coEvery {
                paymentService.processPayment(any(), any(), any(), any())
            } throws RuntimeException("PG 연결 실패")

            handler.handle(processCommand(), ack)

            val reply = captureReply()
            assertFalse(reply.success)
            assertEquals("saga-1", reply.sagaId)
            assertEquals("PG 연결 실패", reply.failureReason)
        }
    }

    // ──────────────────────────────────────────
    // REFUND 커맨드
    // ──────────────────────────────────────────

    @Nested
    inner class Refund {

        @Test
        fun `환불 처리 성공 시 success=true reply 발행`() = runTest {
            coEvery {
                paymentService.refundPayment("saga-1", "order-1", "10000")
            } just runs

            handler.handle(refundCommand(), ack)

            val reply = captureReply()
            assertTrue(reply.success)
            assertEquals("saga-1", reply.sagaId)
            assertTrue(reply.failureReason.isEmpty())
        }

        @Test
        fun `환불 처리 중 예외 발생 시 success=false + failureReason 포함 reply 발행`() = runTest {
            coEvery {
                paymentService.refundPayment(any(), any(), any())
            } throws RuntimeException("환불 실패")

            handler.handle(refundCommand(), ack)

            val reply = captureReply()
            assertFalse(reply.success)
            assertEquals("환불 실패", reply.failureReason)
        }
    }

    // ──────────────────────────────────────────
    // 알 수 없는 커맨드 타입
    // ──────────────────────────────────────────

    @Nested
    inner class UnknownCommand {

        @Test
        fun `UNSPECIFIED 타입 수신 시 success=false reply 발행`() = runTest {
            handler.handle(
                paymentCommand {
                    sagaId = "saga-1"
                    type   = PaymentCommandType.PAYMENT_COMMAND_TYPE_UNSPECIFIED
                    orderId = "order-1"
                    userId  = "user-1"
                    amount  = "10000"
                },
                ack
            )

            val reply = captureReply()
            assertFalse(reply.success)
            assertTrue(reply.failureReason.isNotBlank())
        }
    }

    // ──────────────────────────────────────────
    // 공통 행동 검증
    // ──────────────────────────────────────────

    @Nested
    inner class CommonBehavior {

        @Test
        fun `성공 케이스에서 reply 발행 후 ack 호출`() = runTest {
            coEvery { paymentService.processPayment(any(), any(), any(), any()) } returns "pay-1"

            handler.handle(processCommand(), ack)

            coVerify(exactly = 1) {
                kafkaTemplate.send("payment-reply", "saga-1", any<GeneratedMessage>())
            }
            coVerify(exactly = 1) { ack.acknowledge() }
        }

        @Test
        fun `예외 케이스에서도 reply 발행 후 ack 호출`() = runTest {
            coEvery { paymentService.processPayment(any(), any(), any(), any()) } throws RuntimeException("오류")

            handler.handle(processCommand(), ack)

            coVerify(exactly = 1) {
                kafkaTemplate.send("payment-reply", "saga-1", any<GeneratedMessage>())
            }
            coVerify(exactly = 1) { ack.acknowledge() }
        }
    }

    // ──────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────

    private fun processCommand() = paymentCommand {
        sagaId  = "saga-1"
        type    = PaymentCommandType.PROCESS
        orderId = "order-1"
        userId  = "user-1"
        amount  = "10000"
    }

    private fun refundCommand() = paymentCommand {
        sagaId  = "saga-1"
        type    = PaymentCommandType.REFUND
        orderId = "order-1"
        userId  = "user-1"
        amount  = "10000"
    }

    /** payment-reply 토픽으로 발행된 메시지를 캡처해서 PaymentReply로 반환 */
    private fun captureReply(): PaymentReply {
        val captured = slot<GeneratedMessage>()
        coVerify { kafkaTemplate.send("payment-reply", any(), capture(captured)) }
        return captured.captured as PaymentReply
    }
}
