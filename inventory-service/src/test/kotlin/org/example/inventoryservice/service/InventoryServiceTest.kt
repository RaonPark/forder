package org.example.inventoryservice.service

import common.document.inventory.InventoryHistoryChangeType
import common.document.inventory.InventoryReferenceSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.example.inventoryservice.document.Inventory
import org.example.inventoryservice.dto.AdjustStockRequest
import org.example.inventoryservice.dto.CreateInventoryRequest
import org.example.inventoryservice.repository.InventoryHistoryRepository
import org.example.inventoryservice.repository.InventoryRepository
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@ExtendWith(MockKExtension::class)
class InventoryServiceTest {

    @MockK
    lateinit var inventoryRepository: InventoryRepository

    @MockK
    lateinit var inventoryHistoryRepository: InventoryHistoryRepository

    @InjectMockKs
    lateinit var inventoryService: InventoryService

    // ──────────────────────────────────────────
    // 테스트 픽스처
    // ──────────────────────────────────────────

    private fun inventory(
        inventoryId: String = "inv-1",
        productId: String = "prod-1",
        optionId: String? = "opt-1",
        location: String = "WAREHOUSE_A",
        currentStock: Int = 100,
        reservedStock: Int = 0,
        safetyStock: Int = 10,
    ) = Inventory(
        inventoryId = inventoryId,
        productId = productId,
        optionId = optionId,
        location = location,
        currentStock = currentStock,
        reservedStock = reservedStock,
        safetyStock = safetyStock,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        version = 0L,
    )

    private fun adjustRequest(
        quantityDelta: Int,
        referenceId: String = "ref-001",
        referenceSource: InventoryReferenceSource = InventoryReferenceSource.ADMIN_ADJUSTMENT,
    ) = AdjustStockRequest(
        quantityDelta = quantityDelta,
        referenceId = referenceId,
        referenceSource = referenceSource,
    )

    // ──────────────────────────────────────────
    // createInventory
    // ──────────────────────────────────────────

    @Nested
    inner class CreateInventory {

        @Test
        fun `신규 재고 생성 성공`() = runTest {
            val request = CreateInventoryRequest(
                productId = "prod-1",
                optionId = "opt-1",
                location = "WAREHOUSE_A",
                initialStock = 100,
                safetyStock = 10,
            )
            val capturedInventory = slot<Inventory>() // 저장되는 객체 캡처용

            coEvery {
                inventoryRepository.findByProductIdAndOptionIdAndLocation("prod-1", "opt-1", "WAREHOUSE_A")
            } returns null
            coEvery { inventoryRepository.save(capture(capturedInventory)) } returns inventory()

            inventoryService.createInventory(request)

            // Request → Document 매핑 로직 검증
            with(capturedInventory.captured) {
                assertEquals("prod-1", productId)
                assertEquals("opt-1", optionId)
                assertEquals("WAREHOUSE_A", location)
                assertEquals(100, currentStock)        // initialStock이 currentStock으로
                assertEquals(0, reservedStock)         // 항상 0으로 초기화
                assertEquals(10, safetyStock)
                assertTrue(inventoryId.isNotBlank())   // UUID 생성 확인
            }
        }

        @Test
        fun `동일한 productId+optionId+location 중복 등록 시 CONFLICT`() = runTest {
            val request = CreateInventoryRequest(
                productId = "prod-1",
                optionId = "opt-1",
                location = "WAREHOUSE_A",
                initialStock = 100,
            )
            coEvery {
                inventoryRepository.findByProductIdAndOptionIdAndLocation(any(), any(), any())
            } returns inventory()

            val result = runCatching { inventoryService.createInventory(request) }

            assertIs<ResponseStatusException>(result.exceptionOrNull())
            assertEquals(HttpStatus.CONFLICT, (result.exceptionOrNull() as ResponseStatusException).statusCode)
        }
    }

    // ──────────────────────────────────────────
    // getInventory
    // ──────────────────────────────────────────

    @Nested
    inner class GetInventory {

        @Test
        fun `inventoryId로 재고 조회 성공`() = runTest {
            coEvery { inventoryRepository.findById("inv-1") } returns inventory()

            val response = inventoryService.getInventory("inv-1")

            assertEquals("inv-1", response.inventoryId)
            assertEquals(100, response.currentStock)
        }

        @Test
        fun `존재하지 않는 inventoryId 조회 시 NOT_FOUND`() = runTest {
            coEvery { inventoryRepository.findById("no-such") } returns null

            val result = runCatching { inventoryService.getInventory("no-such") }

            assertIs<ResponseStatusException>(result.exceptionOrNull())
            assertEquals(HttpStatus.NOT_FOUND, (result.exceptionOrNull() as ResponseStatusException).statusCode)
        }
    }

    // ──────────────────────────────────────────
    // adjustStock
    // ──────────────────────────────────────────

    @Nested
    inner class AdjustStock {

        @Test
        fun `양수 delta로 입고 처리 성공`() = runTest {
            val inv = inventory(currentStock = 50)
            coEvery { inventoryRepository.findById("inv-1") } returns inv
            coEvery { inventoryRepository.save(any()) } returns inv.copy(currentStock = 80)
            coEvery { inventoryHistoryRepository.save(any()) } returns mockk()

            val response = inventoryService.adjustStock("inv-1", adjustRequest(quantityDelta = 30))

            assertEquals(80, response.currentStock)
            coVerify {
                inventoryHistoryRepository.save(
                    match { it.changeType == InventoryHistoryChangeType.STOCK_IN }
                )
            }
        }

        @Test
        fun `음수 delta로 출고 처리 성공`() = runTest {
            val inv = inventory(currentStock = 100)
            coEvery { inventoryRepository.findById("inv-1") } returns inv
            coEvery { inventoryRepository.save(any()) } returns inv.copy(currentStock = 60)
            coEvery { inventoryHistoryRepository.save(any()) } returns mockk()

            val response = inventoryService.adjustStock("inv-1", adjustRequest(quantityDelta = -40))

            assertEquals(60, response.currentStock)
            coVerify {
                inventoryHistoryRepository.save(
                    match { it.changeType == InventoryHistoryChangeType.STOCK_OUT_MANUAL }
                )
            }
        }

        @Test
        fun `재고보다 많은 출고 요청 시 UNPROCESSABLE_ENTITY`() = runTest {
            val inv = inventory(currentStock = 10)
            coEvery { inventoryRepository.findById("inv-1") } returns inv

            val result = runCatching { inventoryService.adjustStock("inv-1", adjustRequest(quantityDelta = -50)) }

            assertIs<ResponseStatusException>(result.exceptionOrNull())
            assertEquals(
                HttpStatus.UNPROCESSABLE_ENTITY,
                (result.exceptionOrNull() as ResponseStatusException).statusCode
            )
        }

        @Test
        fun `존재하지 않는 inventoryId에 조정 요청 시 NOT_FOUND`() = runTest {
            coEvery { inventoryRepository.findById("no-such") } returns null

            val result = runCatching { inventoryService.adjustStock("no-such", adjustRequest(quantityDelta = 10)) }

            assertIs<ResponseStatusException>(result.exceptionOrNull())
            assertEquals(HttpStatus.NOT_FOUND, (result.exceptionOrNull() as ResponseStatusException).statusCode)
        }
    }

    // ──────────────────────────────────────────
    // deleteInventory
    // ──────────────────────────────────────────

    @Nested
    inner class DeleteInventory {

        @Test
        fun `재고 삭제 성공`() = runTest {
            coEvery { inventoryRepository.existsById("inv-1") } returns true
            coEvery { inventoryRepository.deleteById("inv-1") } just runs

            inventoryService.deleteInventory("inv-1")

            coVerify(exactly = 1) { inventoryRepository.deleteById("inv-1") }
        }

        @Test
        fun `존재하지 않는 재고 삭제 시 NOT_FOUND`() = runTest {
            coEvery { inventoryRepository.existsById("no-such") } returns false

            val result = runCatching { inventoryService.deleteInventory("no-such") }

            assertIs<ResponseStatusException>(result.exceptionOrNull())
            assertEquals(HttpStatus.NOT_FOUND, (result.exceptionOrNull() as ResponseStatusException).statusCode)
        }
    }

    // ──────────────────────────────────────────
    // reserveStock (Saga)
    // ──────────────────────────────────────────

    @Nested
    inner class ReserveStock {

        @Test
        fun `가용 재고 충분하면 예약 성공 후 true 반환`() = runTest {
            val inv = inventory(currentStock = 100, reservedStock = 0)
            val items = listOf(InventoryService.SagaItem("prod-1", "opt-1", 10))

            every { inventoryRepository.findAllByProductIdAndOptionId("prod-1", "opt-1") } returns flowOf(inv)
            coEvery { inventoryRepository.save(any()) } returns inv.copy(reservedStock = 10)
            coEvery { inventoryHistoryRepository.save(any()) } returns mockk()

            val result = inventoryService.reserveStock("saga-001", items)

            assertTrue(result)
            coVerify {
                inventoryHistoryRepository.save(
                    match { it.changeType == InventoryHistoryChangeType.ORDER_RESERVE }
                )
            }
        }

        @Test
        fun `가용 재고 부족하면 false 반환하고 save 미호출`() = runTest {
            // currentStock=5, reservedStock=0 → 가용재고 5 < 요청 10
            val inv = inventory(currentStock = 5, reservedStock = 0)
            val items = listOf(InventoryService.SagaItem("prod-1", "opt-1", 10))

            every { inventoryRepository.findAllByProductIdAndOptionId("prod-1", "opt-1") } returns flowOf(inv)

            val result = inventoryService.reserveStock("saga-002", items)

            assertFalse(result)
            coVerify(exactly = 0) { inventoryRepository.save(any()) }
            coVerify(exactly = 0) { inventoryHistoryRepository.save(any()) }
        }

        @Test
        fun `예약된 재고가 있어 가용재고 부족하면 false 반환`() = runTest {
            // currentStock=10, reservedStock=8 → 가용재고 2 < 요청 5
            val inv = inventory(currentStock = 10, reservedStock = 8)
            val items = listOf(InventoryService.SagaItem("prod-1", "opt-1", 5))

            every { inventoryRepository.findAllByProductIdAndOptionId("prod-1", "opt-1") } returns flowOf(inv)

            val result = inventoryService.reserveStock("saga-003", items)

            assertFalse(result)
        }

        @Test
        fun `inventoryId referenceSource가 ORDER_SERVICE로 이력 저장`() = runTest {
            val inv = inventory(currentStock = 50, reservedStock = 0)
            val items = listOf(InventoryService.SagaItem("prod-1", "opt-1", 5))

            every { inventoryRepository.findAllByProductIdAndOptionId("prod-1", "opt-1") } returns flowOf(inv)
            coEvery { inventoryRepository.save(any()) } returns inv.copy(reservedStock = 5)
            coEvery { inventoryHistoryRepository.save(any()) } returns mockk()

            inventoryService.reserveStock("saga-004", items)

            coVerify {
                inventoryHistoryRepository.save(
                    match { it.referenceSource == InventoryReferenceSource.ORDER_SERVICE }
                )
            }
        }
    }

    // ──────────────────────────────────────────
    // releaseStock (Saga)
    // ──────────────────────────────────────────

    @Nested
    inner class ReleaseStock {

        @Test
        fun `예약된 재고 정상 해제 성공`() = runTest {
            val inv = inventory(currentStock = 100, reservedStock = 20)
            val items = listOf(InventoryService.SagaItem("prod-1", "opt-1", 10))

            every { inventoryRepository.findAllByProductIdAndOptionId("prod-1", "opt-1") } returns flowOf(inv)
            coEvery { inventoryRepository.save(any()) } returns inv.copy(reservedStock = 10)
            coEvery { inventoryHistoryRepository.save(any()) } returns mockk()

            inventoryService.releaseStock("saga-005", items)

            coVerify {
                inventoryRepository.save(match { it.reservedStock == 10 })
                inventoryHistoryRepository.save(
                    match { it.changeType == InventoryHistoryChangeType.ORDER_CANCEL }
                )
            }
        }

        @Test
        fun `예약 재고가 0이면 해제 건너뜀`() = runTest {
            // reservedStock=0 이면 firstOrNull { it.reservedStock > 0 } 이 null 반환 → continue
            val inv = inventory(currentStock = 100, reservedStock = 0)
            val items = listOf(InventoryService.SagaItem("prod-1", "opt-1", 10))

            every { inventoryRepository.findAllByProductIdAndOptionId("prod-1", "opt-1") } returns flowOf(inv)

            inventoryService.releaseStock("saga-006", items)

            coVerify(exactly = 0) { inventoryRepository.save(any()) }
            coVerify(exactly = 0) { inventoryHistoryRepository.save(any()) }
        }

        @Test
        fun `요청 수량이 예약 재고보다 많으면 minOf로 예약 재고만큼만 해제`() = runTest {
            // reservedStock=5, 요청=20 → releasedQty=5
            val inv = inventory(currentStock = 100, reservedStock = 5)
            val items = listOf(InventoryService.SagaItem("prod-1", "opt-1", 20))

            every { inventoryRepository.findAllByProductIdAndOptionId("prod-1", "opt-1") } returns flowOf(inv)
            coEvery { inventoryRepository.save(any()) } returns inv.copy(reservedStock = 0)
            coEvery { inventoryHistoryRepository.save(any()) } returns mockk()

            inventoryService.releaseStock("saga-007", items)

            coVerify {
                // quantityDelta = -releasedQty = -5 (reservedStock만큼)
                inventoryHistoryRepository.save(match { it.quantityDelta == -5 })
            }
        }
    }
}
