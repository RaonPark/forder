package org.example.deliveryservice.dto

import common.document.delivery.FaultType

data class CompleteInspectionRequest(
    val isResellable: Boolean,
    val faultType: FaultType,
    val inspectorComment: String? = null
)
