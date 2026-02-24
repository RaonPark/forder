package org.example.inventoryservice.service

import common.document.inventory.InventoryHistoryChangeType
import common.document.inventory.InventoryReferenceSource
import common.document.product.StockType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.example.inventoryservice.document.Inventory
import org.example.inventoryservice.document.InventoryHistory
import org.example.inventoryservice.dto.AdjustStockRequest
import org.example.inventoryservice.dto.CreateInventoryRequest
import org.example.inventoryservice.dto.InventoryResponse
import org.example.inventoryservice.dto.toResponse
import org.example.inventoryservice.repository.InventoryHistoryRepository
import org.example.inventoryservice.repository.InventoryRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository,
    private val inventoryHistoryRepository: InventoryHistoryRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // ──────────────────────────────────────────
    // CRUD
    // ──────────────────────────────────────────

    @Transactional
    suspend fun createInventory(request: CreateInventoryRequest): InventoryResponse {
        // 동일 (productId, optionId, location) 중복 방지 - unique index가 있지만 메시지를 명확히
        val existing = inventoryRepository.findByProductIdAndOptionIdAndLocation(
            request.productId, request.optionId, request.location
        )
        if (existing != null) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "이미 등록된 재고입니다 - productId=${request.productId}, location=${request.location}"
            )
        }

        val inventory = Inventory(
            inventoryId   = UUID.randomUUID().toString(),
            productId     = request.productId,
            optionId      = request.optionId,
            location      = request.location,
            currentStock  = request.initialStock,
            reservedStock = 0,
            safetyStock   = request.safetyStock
        )
        return inventoryRepository.save(inventory).toResponse()
    }

    suspend fun getInventory(inventoryId: String): InventoryResponse {
        return inventoryRepository.findById(inventoryId)?.toResponse()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "재고를 찾을 수 없습니다 - inventoryId=$inventoryId")
    }

    fun getInventoriesByProductId(productId: String): Flow<InventoryResponse> {
        return inventoryRepository.findAllByProductId(productId).map { it.toResponse() }
    }

    @Transactional
    suspend fun adjustStock(inventoryId: String, request: AdjustStockRequest): InventoryResponse {
        val inventory = inventoryRepository.findById(inventoryId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "재고를 찾을 수 없습니다 - inventoryId=$inventoryId")

        val newStock = inventory.currentStock + request.quantityDelta
        if (newStock < 0) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "재고 부족 - 현재: ${inventory.currentStock}, 요청: ${request.quantityDelta}"
            )
        }

        val changeType = if (request.quantityDelta >= 0) InventoryHistoryChangeType.STOCK_IN
                         else InventoryHistoryChangeType.STOCK_OUT_MANUAL

        val updated = inventoryRepository.save(inventory.copy(currentStock = newStock))

        inventoryHistoryRepository.save(
            InventoryHistory(
                inventoryHistoryId = UUID.randomUUID().toString(),
                inventoryId        = inventoryId,
                productId          = inventory.productId,
                optionId           = inventory.optionId,
                location           = inventory.location,
                changeType         = changeType,
                quantityDelta      = request.quantityDelta,
                stockType          = StockType.CURRENT_STOCK,
                beforeStock        = inventory.currentStock,
                afterStock         = newStock,
                referenceId        = request.referenceId,
                referenceSource    = request.referenceSource,
                userId             = request.userId
            )
        )

        log.info("[Inventory] 재고 조정 - inventoryId={}, delta={}, after={}", inventoryId, request.quantityDelta, newStock)
        return updated.toResponse()
    }

    @Transactional
    suspend fun deleteInventory(inventoryId: String) {
        if (!inventoryRepository.existsById(inventoryId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "재고를 찾을 수 없습니다 - inventoryId=$inventoryId")
        }
        inventoryRepository.deleteById(inventoryId)
    }

    fun getInventoryHistory(inventoryId: String): Flow<InventoryHistory> {
        return inventoryHistoryRepository.findAllByInventoryIdOrderByTimestampDesc(inventoryId)
    }

    // ──────────────────────────────────────────
    // Saga 전용: 재고 예약 / 해제
    // ──────────────────────────────────────────

    @Transactional
    suspend fun reserveStock(sagaId: String, items: List<SagaItem>): Boolean {
        // 1단계: 전체 가용 재고 검증 (하나라도 부족하면 실패)
        val inventories = mutableMapOf<SagaItem, Inventory>()
        for (item in items) {
            val inventory = inventoryRepository
                .findAllByProductIdAndOptionId(item.productId, item.optionId)
                .firstOrNull { (it.currentStock - it.reservedStock) >= item.quantity }
                ?: run {
                    log.warn("[Saga] 재고 부족 - sagaId={}, productId={}", sagaId, item.productId)
                    return false
                }
            inventories[item] = inventory
        }

        // 2단계: 예약 확정
        for ((item, inventory) in inventories) {
            val updated = inventoryRepository.save(
                inventory.copy(reservedStock = inventory.reservedStock + item.quantity)
            )
            inventoryHistoryRepository.save(
                InventoryHistory(
                    inventoryHistoryId = UUID.randomUUID().toString(),
                    inventoryId        = inventory.inventoryId,
                    productId          = inventory.productId,
                    optionId           = inventory.optionId,
                    location           = inventory.location,
                    changeType         = InventoryHistoryChangeType.ORDER_RESERVE,
                    quantityDelta      = item.quantity,
                    stockType          = StockType.RESERVED_STOCK,
                    beforeStock        = inventory.reservedStock,
                    afterStock         = updated.reservedStock,
                    referenceId        = sagaId,
                    referenceSource    = InventoryReferenceSource.ORDER_SERVICE
                )
            )
        }

        log.info("[Saga] 재고 예약 완료 - sagaId={}, items={}", sagaId, items.size)
        return true
    }

    @Transactional
    suspend fun releaseStock(sagaId: String, items: List<SagaItem>) {
        for (item in items) {
            val inventory = inventoryRepository
                .findAllByProductIdAndOptionId(item.productId, item.optionId)
                .firstOrNull { it.reservedStock > 0 } ?: continue

            val releasedQty = minOf(item.quantity, inventory.reservedStock)
            val updated = inventoryRepository.save(
                inventory.copy(reservedStock = inventory.reservedStock - releasedQty)
            )
            inventoryHistoryRepository.save(
                InventoryHistory(
                    inventoryHistoryId = UUID.randomUUID().toString(),
                    inventoryId        = inventory.inventoryId,
                    productId          = inventory.productId,
                    optionId           = inventory.optionId,
                    location           = inventory.location,
                    changeType         = InventoryHistoryChangeType.ORDER_CANCEL,
                    quantityDelta      = -releasedQty,
                    stockType          = StockType.RESERVED_STOCK,
                    beforeStock        = inventory.reservedStock,
                    afterStock         = updated.reservedStock,
                    referenceId        = sagaId,
                    referenceSource    = InventoryReferenceSource.ORDER_SERVICE
                )
            )
        }
        log.info("[Saga] 재고 해제 완료 - sagaId={}, items={}", sagaId, items.size)
    }

    data class SagaItem(val productId: String, val optionId: String?, val quantity: Int)
}
