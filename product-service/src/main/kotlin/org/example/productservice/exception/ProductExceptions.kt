package org.example.productservice.exception

class ProductNotFoundException(productId: String) :
    RuntimeException("Product not found. productId=$productId")

class ProductStatusConflictException(message: String) :
    RuntimeException(message)
