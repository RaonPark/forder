package common.saga

data class OrderSagaState(
    val sagaId: String,
    var currentStep: SagaStep = SagaStep.INVENTORY_RESERVE,
    var inventoryReserved: Boolean = false,
    var paymentProcessed: Boolean = false,
    var deliveryCreated: Boolean = false,
    var failureReason: String? = null
)
