package org.example.orderservice.exception

class OrderNotFoundException(orderId: String) :
    RuntimeException("Order not found. orderId=$orderId")

class OrderStatusConflictException(message: String) :
    RuntimeException(message)
