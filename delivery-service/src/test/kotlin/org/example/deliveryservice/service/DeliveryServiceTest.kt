package org.example.deliveryservice.service

import common.document.delivery.DeliveryAddress
import common.document.delivery.DeliveryStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.test.runTest
import org.example.deliveryservice.document.Courier
import org.example.deliveryservice.document.Delivery
import org.example.deliveryservice.document.IntegrationType
import org.example.deliveryservice.dto.CreateDeliveryRequest
import org.example.deliveryservice.dto.UpdateDeliveryStatusRequest
import org.example.deliveryservice.exception.CourierNotFoundException
import org.example.deliveryservice.exception.InvalidDeliveryOperationException
import org.example.deliveryservice.repository.CourierRepository
import org.example.deliveryservice.repository.DeliveryHistoryRepository
import org.example.deliveryservice.repository.DeliveryRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import kotlin.test.assertEquals
import kotlin.test.assertIs

@ExtendWith(MockKExtension::class)
class DeliveryServiceTest {

    @MockK lateinit var deliveryRepository: DeliveryRepository
    @MockK lateinit var deliveryHistoryRepository: DeliveryHistoryRepository
    @MockK lateinit var courierRepository: CourierRepository

    private lateinit var sut: DeliveryService

    @BeforeEach
    fun setUp() {
        sut = DeliveryService(deliveryRepository, deliveryHistoryRepository, courierRepository)
    }

    // ──────────────────────────────────────────────────────────
    // 픽스처
    // ──────────────────────────────────────────────────────────

    private val address = DeliveryAddress(
        receiverName  = "홍길동",
        receiverPhone = "010-1234-5678",
        zipCode       = "06234",
        baseAddress   = "서울시 강남구 테헤란로 123",
        detailAddress = "101호"
    )

    private fun delivery(
        deliveryId: String = "delivery-1",
        orderId: String    = "order-1",
        status: DeliveryStatus = DeliveryStatus.PENDING,
        sagaId: String? = null,
        version: Long = 0L
    ) = Delivery(
        deliveryId  = deliveryId,
        orderId     = orderId,
        userId      = "user-1",
        status      = status,
        destination = address,
        sagaId      = sagaId,
        version     = version
    )

    private fun courier(courierId: String = "courier-1") = Courier(
        courierId       = courierId,
        name            = "CJ대한통운",
        integrationType = IntegrationType.NONE
    )

    // ──────────────────────────────────────────────────────────
    // createDelivery
    // ──────────────────────────────────────────────────────────

    @Test
    fun `courierId 미지정 시 활성 기사를 자동 배정한다`() = runTest {
        val activeCourier = courier()
        coEvery { courierRepository.findFirstByIsActiveTrue() } returns activeCourier
        coEvery { deliveryRepository.save(any()) } answers { firstArg() }

        val response = sut.createDelivery(
            CreateDeliveryRequest(orderId = "order-1", userId = "user-1", destination = address, items = emptyList())
        )

        assertEquals(activeCourier.courierId, response.courierId)
    }

    // ──────────────────────────────────────────────────────────
    // updateStatus
    // ──────────────────────────────────────────────────────────

    @Test
    fun `유효한 상태 전이(PENDING → PICKED_UP)가 성공한다`() = runTest {
        coEvery { deliveryRepository.findById("delivery-1") } returns delivery()
        coEvery { deliveryRepository.save(any()) } answers { firstArg() }
        coEvery { deliveryHistoryRepository.save(any()) } answers { firstArg() }

        val response = sut.updateStatus(
            "delivery-1",
            UpdateDeliveryStatusRequest(status = DeliveryStatus.PICKED_UP, location = "인천 물류센터")
        )

        assertEquals(DeliveryStatus.PICKED_UP, response.status)
    }

    @Test
    fun `허용되지 않은 상태 전이는 InvalidDeliveryOperationException을 던진다`() = runTest {
        coEvery { deliveryRepository.findById("delivery-1") } returns delivery(status = DeliveryStatus.DELIVERED)

        val result = runCatching {
            sut.updateStatus(
                "delivery-1",
                UpdateDeliveryStatusRequest(status = DeliveryStatus.PICKED_UP, location = "서울")
            )
        }

        assertIs<InvalidDeliveryOperationException>(result.exceptionOrNull())
    }

    // ──────────────────────────────────────────────────────────
    // assignCourier
    // ──────────────────────────────────────────────────────────

    @Test
    fun `존재하지 않는 courierId 배정 시 CourierNotFoundException을 던진다`() = runTest {
        coEvery { deliveryRepository.findById("delivery-1") } returns delivery()
        coEvery { courierRepository.findById("ghost") } returns null

        val result = runCatching { sut.assignCourier("delivery-1", "ghost") }

        assertIs<CourierNotFoundException>(result.exceptionOrNull())
    }

    @Test
    fun `PENDING이 아닌 배송에 기사 배정 시 InvalidDeliveryOperationException을 던진다`() = runTest {
        coEvery { deliveryRepository.findById("delivery-1") } returns delivery(status = DeliveryStatus.IN_TRANSIT)

        val result = runCatching { sut.assignCourier("delivery-1", "courier-1") }

        assertIs<InvalidDeliveryOperationException>(result.exceptionOrNull())
    }

    // ──────────────────────────────────────────────────────────
    // cancelDeliveryFromSaga
    // ──────────────────────────────────────────────────────────

    @Test
    fun `이미 RETURNED_TO_SENDER 상태면 save 없이 early return한다(멱등성)`() = runTest {
        coEvery { deliveryRepository.findByOrderId("order-1") } returns
            delivery(status = DeliveryStatus.RETURNED_TO_SENDER)

        sut.cancelDeliveryFromSaga("order-1")

        coVerify(exactly = 0) { deliveryRepository.save(any()) }
    }

    @Test
    fun `DELIVERED 상태의 배송은 취소할 수 없다`() = runTest {
        coEvery { deliveryRepository.findByOrderId("order-1") } returns delivery(status = DeliveryStatus.DELIVERED)

        val result = runCatching { sut.cancelDeliveryFromSaga("order-1") }

        assertIs<InvalidDeliveryOperationException>(result.exceptionOrNull())
    }

    @Test
    fun `OptimisticLockingFailureException 후 재조회 시 이미 취소됐으면 정상 반환한다(Fix 1-1)`() = runTest {
        // 첫 번째 findByOrderId: PENDING, 두 번째(재조회): 이미 RETURNED_TO_SENDER
        coEvery { deliveryRepository.findByOrderId("order-1") } returnsMany listOf(
            delivery(status = DeliveryStatus.PENDING),
            delivery(status = DeliveryStatus.RETURNED_TO_SENDER)
        )
        coEvery { deliveryRepository.save(any()) } throws OptimisticLockingFailureException("version conflict")

        // 예외 없이 정상 완료되어야 한다
        sut.cancelDeliveryFromSaga("order-1")
    }

    @Test
    fun `OptimisticLockingFailureException 후 재조회 시 여전히 미취소면 예외를 propagate한다`() = runTest {
        // findByOrderId가 항상 PENDING 반환 → 재조회해도 취소 안 됨
        coEvery { deliveryRepository.findByOrderId("order-1") } returns delivery(status = DeliveryStatus.PENDING)
        coEvery { deliveryRepository.save(any()) } throws OptimisticLockingFailureException("version conflict")

        val result = runCatching { sut.cancelDeliveryFromSaga("order-1") }

        assertIs<OptimisticLockingFailureException>(result.exceptionOrNull())
    }

    // ──────────────────────────────────────────────────────────
    // createDeliveryFromSaga
    // ──────────────────────────────────────────────────────────

    @Test
    fun `동일 sagaId 중복 도착 시 DuplicateKeyException을 catch하고 기존 deliveryId를 반환한다`() = runTest {
        val existing = delivery(sagaId = "saga-1")
        // 첫 번째 findBySagaId: null(미처리), 두 번째(DuplicateKey 후 재조회): 기존 document 반환
        coEvery { deliveryRepository.findBySagaId("saga-1") } returnsMany listOf(null, existing)
        coEvery { courierRepository.findFirstByIsActiveTrue() } returns null
        coEvery { deliveryRepository.save(any()) } throws DuplicateKeyException("duplicate sagaId index")

        val result = sut.createDeliveryFromSaga(
            sagaId          = "saga-1",
            orderId         = "order-1",
            userId          = "user-1",
            receiverName    = "홍길동",
            receiverPhone   = "010-0000-0000",
            deliveryAddress = "서울시 강남구"
        )

        assertEquals(existing.deliveryId, result)
    }
}
