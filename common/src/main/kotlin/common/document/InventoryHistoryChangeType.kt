package common.document

enum class InventoryHistoryChangeType {
    ORDER_RESERVE,
    ORDER_CANCEL,
    STOCK_IN,
    STOCK_OUT_MANUAL,
    AUDIT_ADJUSTMENT
}