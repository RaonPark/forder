package org.example.deliveryservice.exception

class DeliveryNotFoundException(message: String) : RuntimeException(message)
class ReturnDeliveryNotFoundException(message: String) : RuntimeException(message)
class CourierNotFoundException(message: String) : RuntimeException(message)
class InvalidDeliveryOperationException(message: String) : RuntimeException(message)
