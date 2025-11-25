package common.document

import java.time.Instant

data class CancellationState(
    val initiatedAt: Instant,

    var deliveryCancelled: Boolean = false,
    var deliveryCancelledAt: Instant? = null,
    var deliveryCancellationError: String? = null,

    var paymentRefunded: Boolean = false,
    var paymentRefundedAt: Instant? = null,
    var paymentRefundTxId: String? = null,
    var paymentRefundError: String? = null,

    var inventoryRestored: Boolean = false,
    var inventoryRestoredAt: Instant? = null,
    var inventoryRestorationError: String? = null,

    var completedAt: Instant? = null,
    var compensationStatus: CompensationStatus = CompensationStatus.IN_PROGRESS
)
