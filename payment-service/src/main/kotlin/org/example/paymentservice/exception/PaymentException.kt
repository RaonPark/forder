package org.example.paymentservice.exception

class PaymentNotFoundException(message: String) : RuntimeException(message)

class InvalidPaymentOperationException(message: String) : RuntimeException(message)
