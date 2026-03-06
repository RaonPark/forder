package org.example.deliveryservice.saga

import com.google.protobuf.GeneratedMessage
import common.saga.command.DeliveryCommand
import common.saga.command.DeliveryCommandType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.example.deliveryservice.exception.InvalidDeliveryOperationException
import org.example.deliveryservice.service.DeliveryService
import org.example.deliveryservice.service.ReturnDeliveryService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture
import kotlin.test.assertIs

@ExtendWith(MockKExtension::class)
class DeliveryCommandHandlerTest {

    @MockK lateinit var deliveryService: DeliveryService
    @MockK lateinit var returnDeliveryService: ReturnDeliveryService
    @MockK lateinit var kafkaTemplate: KafkaTemplate<String, GeneratedMessage>

    private lateinit var handler: DeliveryCommandHandler
    private lateinit var ack: Acknowledgment

    @BeforeEach
    fun setUp() {
        handler = DeliveryCommandHandler(deliveryService, returnDeliveryService, kafkaTemplate)
        ack = mockk()
        every { ack.acknowledge() } just Runs
    }

    // ──────────────────────────────────────────────────────────
    // 픽스처
    // ──────────────────────────────────────────────────────────

    private fun command(type: DeliveryCommandType) = DeliveryCommand.newBuilder()
        .setSagaId("saga-1")
        .setOrderId("order-1")
        .setUserId("user-1")
        .setReceiverName("홍길동")
        .setReceiverPhone("010-1234-5678")
        .setDeliveryAddress("서울시 강남구")
        .setType(type)
        .build()

    private fun successFuture(): CompletableFuture<SendResult<String, GeneratedMessage>> =
        CompletableFuture.completedFuture(mockk())

    /** Kafka 브로커 장애를 시뮬레이션하는 실패 Future */
    private fun failedFuture(): CompletableFuture<SendResult<String, GeneratedMessage>> =
        CompletableFuture<SendResult<String, GeneratedMessage>>()
            .also { it.completeExceptionally(RuntimeException("Kafka 브로커 연결 실패")) }

    // ──────────────────────────────────────────────────────────
    // CREATE
    // ──────────────────────────────────────────────────────────

    @Test
    fun `CREATE 성공 시 reply를 전송하고 ack를 commit한다`() = runTest {
        coEvery {
            deliveryService.createDeliveryFromSaga(any(), any(), any(), any(), any(), any())
        } returns "delivery-1"
        every { kafkaTemplate.send(any<String>(), any<String>(), any()) } returns successFuture()

        handler.handle(command(DeliveryCommandType.CREATE), ack)

        verify { kafkaTemplate.send("delivery-reply", "saga-1", any()) }
        verify { ack.acknowledge() }
    }

    @Test
    fun `CREATE 도메인 실패 시 success=false reply를 전송하고 ack를 commit한다(Fix 3-2)`() = runTest {
        coEvery {
            deliveryService.createDeliveryFromSaga(any(), any(), any(), any(), any(), any())
        } throws InvalidDeliveryOperationException("유효하지 않은 배송 요청")
        every { kafkaTemplate.send(any<String>(), any<String>(), any()) } returns successFuture()

        handler.handle(command(DeliveryCommandType.CREATE), ack)

        // 도메인 실패는 reply를 보내고 ack — Order-service가 보상 트랜잭션을 수행해야 함
        verify { kafkaTemplate.send("delivery-reply", "saga-1", any()) }
        verify { ack.acknowledge() }
    }

    @Test
    fun `CREATE 인프라 오류 시 예외가 propagate되고 ack가 commit되지 않는다(Fix 3-2)`() = runTest {
        coEvery {
            deliveryService.createDeliveryFromSaga(any(), any(), any(), any(), any(), any())
        } throws RuntimeException("MongoDB 연결 실패")

        val result = runCatching { handler.handle(command(DeliveryCommandType.CREATE), ack) }

        // 인프라 예외 → propagate → Kafka DefaultErrorHandler가 재시도
        assertIs<RuntimeException>(result.exceptionOrNull())
        verify(exactly = 0) { ack.acknowledge() }
    }

    // ──────────────────────────────────────────────────────────
    // CANCEL
    // ──────────────────────────────────────────────────────────

    @Test
    fun `CANCEL 성공 시 reply를 전송하고 ack를 commit한다`() = runTest {
        coEvery { deliveryService.cancelDeliveryFromSaga(any()) } just Runs
        every { kafkaTemplate.send(any<String>(), any<String>(), any()) } returns successFuture()

        handler.handle(command(DeliveryCommandType.CANCEL), ack)

        verify { kafkaTemplate.send("delivery-reply", "saga-1", any()) }
        verify { ack.acknowledge() }
    }

    @Test
    fun `CANCEL 도메인 실패 시 success=false reply를 전송하고 ack를 commit한다(Fix 3-2)`() = runTest {
        coEvery {
            deliveryService.cancelDeliveryFromSaga(any())
        } throws InvalidDeliveryOperationException("이미 배달 완료된 배송은 취소할 수 없습니다")
        every { kafkaTemplate.send(any<String>(), any<String>(), any()) } returns successFuture()

        handler.handle(command(DeliveryCommandType.CANCEL), ack)

        verify { kafkaTemplate.send("delivery-reply", "saga-1", any()) }
        verify { ack.acknowledge() }
    }

    @Test
    fun `Kafka send 실패 시 예외가 propagate되고 ack가 commit되지 않는다(Fix 3-1)`() = runTest {
        coEvery { deliveryService.cancelDeliveryFromSaga(any()) } just Runs
        every { kafkaTemplate.send(any<String>(), any<String>(), any()) } returns failedFuture()

        val result = runCatching { handler.handle(command(DeliveryCommandType.CANCEL), ack) }

        // send 실패 → offset commit 안 됨 → Kafka가 커맨드 재전달
        assertIs<Exception>(result.exceptionOrNull())
        verify(exactly = 0) { ack.acknowledge() }
    }
}
