package common.document.delivery

enum class DeliveryStatus {
    PENDING,
    PICKED_UP,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    DELIVERY_FAILED,
    RETURNED_TO_SENDER
}