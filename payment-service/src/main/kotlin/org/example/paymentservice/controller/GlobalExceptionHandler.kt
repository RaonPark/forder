package org.example.paymentservice.controller

import org.example.paymentservice.dto.ErrorResponse
import org.example.paymentservice.exception.InvalidPaymentOperationException
import org.example.paymentservice.exception.PaymentNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(PaymentNotFoundException::class)
    fun handleNotFound(e: PaymentNotFoundException): ResponseEntity<ErrorResponse> {
        log.warn("[Payment] 결제 없음 - {}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("PAYMENT_NOT_FOUND", e.message ?: "결제를 찾을 수 없습니다"))
    }

    @ExceptionHandler(InvalidPaymentOperationException::class)
    fun handleInvalidOperation(e: InvalidPaymentOperationException): ResponseEntity<ErrorResponse> {
        log.warn("[Payment] 잘못된 요청 - {}", e.message)
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse("INVALID_PAYMENT_OPERATION", e.message ?: "잘못된 요청입니다"))
    }

    // @Version 충돌: 동시 요청으로 낙관적 락 실패 → 클라이언트 재시도 유도
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLocking(e: OptimisticLockingFailureException): ResponseEntity<ErrorResponse> {
        log.warn("[Payment] 동시 요청 충돌 - {}", e.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("CONCURRENT_CONFLICT", "동시 요청이 충돌했습니다. 다시 시도해주세요."))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("[Payment] 처리되지 않은 예외", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다"))
    }
}
