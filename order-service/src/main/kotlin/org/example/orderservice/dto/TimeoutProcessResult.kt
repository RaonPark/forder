package org.example.orderservice.dto

enum class TimeoutOutcome {
    CANCELLED,
    CANCELLATION_FAILED,
    RETURN_FAILED,
    SKIPPED
}

data class TimeoutProcessResult(
    val processedCount: Int,
    val cancelledOrders: List<String>,
    val cancellationFailedOrders: List<String>,
    val returnFailedOrders: List<String>,
    val skippedOrders: List<String>
)
