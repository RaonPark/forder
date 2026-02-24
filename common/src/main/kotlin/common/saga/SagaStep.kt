package common.saga

enum class SagaStep {
    INVENTORY_RESERVE,
    PAYMENT_PROCESS,
    DELIVERY_CREATE,
    COMPENSATING,
    COMPLETED,
    FAILED
}
