package common.document

import java.time.Instant

data class ReturnState(
    val initiatedAt: Instant,

    var pickupScheduled: Boolean = false,
    var pickupScheduledAt: Instant? = null,
    var pickupError: String? = null,

    var itemsReceived: Boolean = false,
    var itemsReceivedAt: Instant? = null,

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