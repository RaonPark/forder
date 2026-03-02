package org.example.deliveryservice.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.example.deliveryservice.document.Courier
import org.example.deliveryservice.document.IntegrationType
import org.example.deliveryservice.dto.CourierResponse
import org.example.deliveryservice.exception.CourierNotFoundException
import org.example.deliveryservice.repository.CourierRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@ExtendWith(MockKExtension::class)
class CourierServiceTest {

    @MockK lateinit var courierRepository: CourierRepository

    private lateinit var sut: CourierService

    @BeforeEach
    fun setUp() {
        sut = CourierService(courierRepository)
    }

    // ──────────────────────────────────────────────────────────
    // 픽스처
    // ──────────────────────────────────────────────────────────

    private fun courier(
        courierId: String = "courier-1",
        isActive: Boolean = true
    ) = Courier(
        courierId       = courierId,
        name            = "CJ대한통운",
        integrationType = IntegrationType.NONE,
        isActive        = isActive
    )

    // ──────────────────────────────────────────────────────────
    // getCourier
    // ──────────────────────────────────────────────────────────

    @Test
    fun `존재하지 않는 courierId 조회 시 CourierNotFoundException을 던진다`() = runTest {
        coEvery { courierRepository.findById("ghost") } returns null

        val result = runCatching { sut.getCourier("ghost") }

        assertIs<CourierNotFoundException>(result.exceptionOrNull())
    }

    // ──────────────────────────────────────────────────────────
    // deactivateCourier
    // ──────────────────────────────────────────────────────────

    @Test
    fun `deactivateCourier가 isActive=false로 저장한다`() = runTest {
        val activeCourier = courier()
        coEvery { courierRepository.findById("courier-1") } returns activeCourier
        coEvery { courierRepository.save(any()) } answers { firstArg() }

        val response = sut.deactivateCourier("courier-1")

        assertFalse(response.isActive)
        coVerify { courierRepository.save(match { !it.isActive }) }
    }

    // ──────────────────────────────────────────────────────────
    // listActiveCouriers — Document 미노출 확인 (Fix 4-3)
    // ──────────────────────────────────────────────────────────

    @Test
    fun `listActiveCouriers가 CourierResponse Flow를 반환한다`() = runTest {
        every { courierRepository.findAllByIsActiveTrue() } returns flowOf(courier())

        val result = sut.listActiveCouriers().toList()

        assertEquals(1, result.size)
        assertIs<CourierResponse>(result.first())
    }
}
