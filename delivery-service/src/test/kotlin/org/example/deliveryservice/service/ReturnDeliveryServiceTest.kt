package org.example.deliveryservice.service

import common.document.delivery.DeliveryAddress
import common.document.delivery.FaultType
import common.document.delivery.ReturnDeliveryStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.example.deliveryservice.document.ReturnDelivery
import org.example.deliveryservice.dto.CompleteInspectionRequest
import org.example.deliveryservice.dto.CreateReturnDeliveryRequest
import org.example.deliveryservice.dto.ReturnDeliveryResponse
import org.example.deliveryservice.exception.InvalidDeliveryOperationException
import org.example.deliveryservice.repository.CourierRepository
import org.example.deliveryservice.repository.DeliveryRepository
import org.example.deliveryservice.repository.ReturnDeliveryRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

@ExtendWith(MockKExtension::class)
class ReturnDeliveryServiceTest {

    @MockK lateinit var returnDeliveryRepository: ReturnDeliveryRepository
    @MockK lateinit var deliveryRepository: DeliveryRepository
    @MockK lateinit var courierRepository: CourierRepository

    private lateinit var sut: ReturnDeliveryService

    @BeforeEach
    fun setUp() {
        sut = ReturnDeliveryService(returnDeliveryRepository, deliveryRepository, courierRepository)
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

    private fun returnDelivery(status: ReturnDeliveryStatus = ReturnDeliveryStatus.PICKUP_REQUESTED) =
        ReturnDelivery(
            returnDeliveryId   = "rd-1",
            originalDeliveryId = "d-1",
            orderId            = "order-1",
            returnRequestId    = "rr-1",
            pickupAddress      = address,
            status             = status
        )

    // ──────────────────────────────────────────────────────────
    // completeInspection
    // ──────────────────────────────────────────────────────────

    @Test
    fun `ARRIVED_AT_WAREHOUSE가 아닌 상태에서 검수 완료 시 InvalidDeliveryOperationException을 던진다`() = runTest {
        coEvery { returnDeliveryRepository.findById("rd-1") } returns
            returnDelivery(status = ReturnDeliveryStatus.RETURNING)

        val result = runCatching {
            sut.completeInspection("rd-1", CompleteInspectionRequest(isResellable = true, faultType = FaultType.OTHER))
        }

        assertIs<InvalidDeliveryOperationException>(result.exceptionOrNull())
    }

    @Test
    fun `재판매 가능 검수 완료 시 INSPECTION_COMPLETED 상태가 된다`() = runTest {
        val arrived = returnDelivery(status = ReturnDeliveryStatus.ARRIVED_AT_WAREHOUSE)
        coEvery { returnDeliveryRepository.findById("rd-1") } returns arrived
        coEvery { returnDeliveryRepository.save(any()) } answers { firstArg() }

        val response = sut.completeInspection(
            "rd-1",
            CompleteInspectionRequest(isResellable = true, faultType = FaultType.CUSTOMER_CHANGE_OF_MIND)
        )

        assertEquals(ReturnDeliveryStatus.INSPECTION_COMPLETED, response.status)
    }

    // ──────────────────────────────────────────────────────────
    // updateStatus
    // ──────────────────────────────────────────────────────────

    @Test
    fun `허용되지 않은 반품 상태 전이는 InvalidDeliveryOperationException을 던진다`() = runTest {
        coEvery { returnDeliveryRepository.findById("rd-1") } returns
            returnDelivery(status = ReturnDeliveryStatus.PICKUP_REQUESTED)

        // PICKUP_REQUESTED → ARRIVED_AT_WAREHOUSE 는 허용 안 됨 (PICKUP_COMPLETED를 거쳐야 함)
        val result = runCatching {
            sut.updateStatus("rd-1", ReturnDeliveryStatus.ARRIVED_AT_WAREHOUSE)
        }

        assertIs<InvalidDeliveryOperationException>(result.exceptionOrNull())
    }

    // ──────────────────────────────────────────────────────────
    // getReturnDeliveriesByOrderId — Document 미노출 확인 (Fix 4-3)
    // ──────────────────────────────────────────────────────────

    @Test
    fun `getReturnDeliveriesByOrderId가 ReturnDeliveryResponse Flow를 반환한다`() = runTest {
        every { returnDeliveryRepository.findAllByOrderId("order-1") } returns flowOf(returnDelivery())

        val result = sut.getReturnDeliveriesByOrderId("order-1").toList()

        assertEquals(1, result.size)
        assertIs<ReturnDeliveryResponse>(result.first())
    }
}
