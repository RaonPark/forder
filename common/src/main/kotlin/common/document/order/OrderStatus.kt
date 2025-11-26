package common.document.order

enum class OrderStatus {
    PENDING,
    PAYMENT_COMPLETED,
    PREPARING,
    SHIPPING,
    DELIVERED,
    CANCELLING,           // Cancellation in progress
    CANCELLED,            // Fully cancelled
    CANCELLATION_FAILED,  // Cancellation saga failed
    RETURN_REQUESTED,
    RETURN_IN_PROGRESS,   // Return saga in progress
    RETURN_COMPLETED,
    RETURN_FAILED         // Return saga failed
}