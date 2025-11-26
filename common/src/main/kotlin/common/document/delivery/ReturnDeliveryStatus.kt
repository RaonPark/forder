package common.document.delivery

enum class ReturnDeliveryStatus {
    PICKUP_REQUESTED,
    PICKUP_COMPLETED,
    RETURNING,
    ARRIVED_AT_WAREHOUSE,
    INSPECTION_COMPLETED,
    INSPECTION_FAILED
}