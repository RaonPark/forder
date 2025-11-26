package common.document.delivery

data class InspectionResult(
    val isResellable: Boolean,
    val faultType: FaultType,
    val inspectorComment: String? = null
)
