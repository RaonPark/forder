package org.example.orderservice.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

data class ErrorResponse(
    val code: String,
    val message: String,
    val timestamp: Instant = Instant.now()
)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(OrderNotFoundException::class)
    fun handleOrderNotFound(ex: OrderNotFoundException): ResponseEntity<ErrorResponse> {
        log.warn("[Order] Not found - {}", ex.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(code = "ORDER_NOT_FOUND", message = ex.message ?: "Order not found"))
    }

    @ExceptionHandler(OrderStatusConflictException::class)
    fun handleOrderStatusConflict(ex: OrderStatusConflictException): ResponseEntity<ErrorResponse> {
        log.warn("[Order] Status conflict - {}", ex.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse(code = "ORDER_STATUS_CONFLICT", message = ex.message ?: "Order status conflict"))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("[Order] Unexpected error", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(code = "INTERNAL_ERROR", message = "An unexpected error occurred"))
    }
}
