package common.document.delivery

data class EnrichedDeliveryItem(
    val productId: String,
    val productName: String,
    val quantity: Int,
    val imageUrl: String?
)