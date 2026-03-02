package org.example.deliveryservice.service

import common.document.delivery.DeliveryAddress
import common.document.delivery.DeliveryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.example.deliveryservice.TestcontainersConfiguration
import org.example.deliveryservice.dto.UpdateDeliveryStatusRequest
import org.example.deliveryservice.exception.InvalidDeliveryOperationException
import org.example.deliveryservice.repository.DeliveryHistoryRepository
import org.example.deliveryservice.repository.DeliveryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration::class)
class DeliveryServiceIntegrationTest {

    @Autowired lateinit var deliveryService: DeliveryService
    @Autowired lateinit var deliveryRepository: DeliveryRepository
    @Autowired lateinit var deliveryHistoryRepository: DeliveryHistoryRepository

    private val address = DeliveryAddress(
        receiverName  = "홍길동",
        receiverPhone = "010-1234-5678",
        zipCode       = "06234",
        baseAddress   = "서울시 강남구 테헤란로 123",
        detailAddress = "101호"
    )

    @AfterEach
    fun cleanUp() = runBlocking {
        deliveryRepository.deleteAll()
        deliveryHistoryRepository.deleteAll()
    }

    // ──────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────

    private suspend fun createSagaDelivery(
        sagaId: String = UUID.randomUUID().toString(),
        orderId: String = UUID.randomUUID().toString()
    ) = deliveryService.createDeliveryFromSaga(
        sagaId          = sagaId,
        orderId         = orderId,
        userId          = "user-1",
        receiverName    = address.receiverName,
        receiverPhone   = address.receiverPhone,
        deliveryAddress = address.baseAddress
    )

    // ──────────────────────────────────────────────────────────
    // createDeliveryFromSaga
    // ──────────────────────────────────────────────────────────

    @Test
    fun `createDeliveryFromSaga - 배송이 생성되고 MongoDB에 저장된다`() = runTest {
        val sagaId  = UUID.randomUUID().toString()
        val orderId = UUID.randomUUID().toString()

        val deliveryId = createSagaDelivery(sagaId = sagaId, orderId = orderId)

        val saved = deliveryRepository.findByOrderId(orderId)
        assertNotNull(saved)
        assertEquals(deliveryId, saved.deliveryId)
        assertEquals(sagaId, saved.sagaId)
        assertEquals(DeliveryStatus.PENDING, saved.status)
    }

    @Test
    fun `createDeliveryFromSaga - 동일 sagaId 두 번 호출 시 같은 deliveryId를 반환한다(멱등성)`() = runTest {
        val sagaId  = UUID.randomUUID().toString()
        val orderId = UUID.randomUUID().toString()

        val first  = createSagaDelivery(sagaId = sagaId, orderId = orderId)
        val second = createSagaDelivery(sagaId = sagaId, orderId = orderId)

        assertEquals(first, second, "두 번째 호출은 첫 번째와 동일한 deliveryId를 반환해야 한다")
        assertEquals(1L, deliveryRepository.count(), "DB에 document가 1개만 존재해야 한다")
    }

    // ──────────────────────────────────────────────────────────
    // cancelDeliveryFromSaga
    // ──────────────────────────────────────────────────────────

    @Test
    fun `cancelDeliveryFromSaga - 정상 취소 후 RETURNED_TO_SENDER 상태가 된다`() = runTest {
        val orderId = UUID.randomUUID().toString()
        createSagaDelivery(orderId = orderId)

        deliveryService.cancelDeliveryFromSaga(orderId)

        val delivery = deliveryRepository.findByOrderId(orderId)
        assertEquals(DeliveryStatus.RETURNED_TO_SENDER, delivery?.status)
    }

    @Test
    fun `cancelDeliveryFromSaga - 존재하지 않는 orderId는 조용히 무시한다`() = runTest {
        // 예외 없이 완료되어야 한다
        deliveryService.cancelDeliveryFromSaga("non-existent-order-${UUID.randomUUID()}")
    }

    @Test
    fun `cancelDeliveryFromSaga - 이미 취소된 배송을 다시 취소해도 예외가 발생하지 않는다(멱등성)`() = runTest {
        val orderId = UUID.randomUUID().toString()
        createSagaDelivery(orderId = orderId)
        deliveryService.cancelDeliveryFromSaga(orderId)

        // 두 번째 취소도 예외 없이 완료되어야 한다
        deliveryService.cancelDeliveryFromSaga(orderId)

        val delivery = deliveryRepository.findByOrderId(orderId)
        assertEquals(DeliveryStatus.RETURNED_TO_SENDER, delivery?.status)
    }

    @Test
    fun `cancelDeliveryFromSaga - 동시 취소 요청 모두 성공한다(Fix 1-1)`() = runTest {
        val orderId = UUID.randomUUID().toString()
        createSagaDelivery(orderId = orderId)

        // Dispatchers.IO 로 실제 병렬 실행 보장
        val results = withContext(Dispatchers.IO) {
            coroutineScope {
                awaitAll(
                    async { runCatching { deliveryService.cancelDeliveryFromSaga(orderId) } },
                    async { runCatching { deliveryService.cancelDeliveryFromSaga(orderId) } }
                )
            }
        }

        results.forEachIndexed { idx, result ->
            assertTrue(
                result.isSuccess,
                "취소 ${idx + 1}번째 실패: ${result.exceptionOrNull()?.message}"
            )
        }

        val delivery = deliveryRepository.findByOrderId(orderId)
        assertEquals(DeliveryStatus.RETURNED_TO_SENDER, delivery?.status)
    }

    @Test
    fun `cancelDeliveryFromSaga - DELIVERED 상태는 취소할 수 없다`() = runTest {
        val orderId    = UUID.randomUUID().toString()
        val deliveryId = createSagaDelivery(orderId = orderId)

        // PENDING → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED
        deliveryService.updateStatus(deliveryId, UpdateDeliveryStatusRequest(DeliveryStatus.PICKED_UP, "센터"))
        deliveryService.updateStatus(deliveryId, UpdateDeliveryStatusRequest(DeliveryStatus.IN_TRANSIT, "이동 중"))
        deliveryService.updateStatus(deliveryId, UpdateDeliveryStatusRequest(DeliveryStatus.OUT_FOR_DELIVERY, "배달 중"))
        deliveryService.updateStatus(deliveryId, UpdateDeliveryStatusRequest(DeliveryStatus.DELIVERED, "배달 완료"))

        val result = runCatching { deliveryService.cancelDeliveryFromSaga(orderId) }

        assertIs<InvalidDeliveryOperationException>(result.exceptionOrNull())
    }

    // ──────────────────────────────────────────────────────────
    // updateStatus
    // ──────────────────────────────────────────────────────────

    @Test
    fun `updateStatus - 상태 변경이 저장되고 이력이 기록된다`() = runTest {
        val orderId    = UUID.randomUUID().toString()
        val deliveryId = createSagaDelivery(orderId = orderId)

        deliveryService.updateStatus(
            deliveryId,
            UpdateDeliveryStatusRequest(status = DeliveryStatus.PICKED_UP, location = "인천 물류센터")
        )

        val delivery = deliveryRepository.findById(deliveryId)
        assertEquals(DeliveryStatus.PICKED_UP, delivery?.status)

        val history = deliveryHistoryRepository
            .findAllByDeliveryIdOrderByTimestampAsc(deliveryId)
            .toList()
        assertEquals(1, history.size)
        assertEquals(DeliveryStatus.PICKED_UP, history.first().status)
        assertEquals("인천 물류센터", history.first().location)
    }
}
