package common.document.delivery

data class DeliveryAddress(
    val receiverName: String,
    val receiverPhone: String,
    val zipCode: String,
    val baseAddress: String,
    val detailAddress: String,
    val message: String? = null
)
