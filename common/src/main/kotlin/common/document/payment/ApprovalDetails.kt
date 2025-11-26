package common.document.payment

data class ApprovalDetails(
    val cardNumber: String,
    val cardCompanyName: String,
    val installmentMonth: Int,
    val approvalNumber: String? = null
)
