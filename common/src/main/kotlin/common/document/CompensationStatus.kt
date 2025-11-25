package common.document

enum class CompensationStatus {
    IN_PROGRESS,      // Saga is running
    COMPLETED,        // All compensations successful
    PARTIALLY_FAILED, // Some steps failed
    FAILED,           // Critical failure
    PENDING_RETRY     // Waiting for retry
}
