package common.document.delivery

enum class FaultType {
    CUSTOMER_CHANGE_OF_MIND,
    CUSTOMER_MISORDER,
    PRODUCT_DEFECT,
    SELLER_FAULT,
    COURIER_FAULT,
    SYSTEM_ERROR,
    OTHER
}