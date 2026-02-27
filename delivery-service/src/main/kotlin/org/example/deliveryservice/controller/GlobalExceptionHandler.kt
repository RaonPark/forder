package org.example.deliveryservice.controller

import org.example.deliveryservice.dto.ErrorResponse
import org.example.deliveryservice.exception.CourierNotFoundException
import org.example.deliveryservice.exception.DeliveryNotFoundException
import org.example.deliveryservice.exception.InvalidDeliveryOperationException
import org.example.deliveryservice.exception.ReturnDeliveryNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DeliveryNotFoundException::class)
    fun handleDeliveryNotFound(e: DeliveryNotFoundException): ResponseEntity<ErrorResponse> {
        log.warn("[Delivery] 배송 없음 - {}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("DELIVERY_NOT_FOUND", e.message ?: "배송을 찾을 수 없습니다"))
    }

    @ExceptionHandler(ReturnDeliveryNotFoundException::class)
    fun handleReturnDeliveryNotFound(e: ReturnDeliveryNotFoundException): ResponseEntity<ErrorResponse> {
        log.warn("[Delivery] 반품 배송 없음 - {}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("RETURN_DELIVERY_NOT_FOUND", e.message ?: "반품 배송을 찾을 수 없습니다"))
    }

    @ExceptionHandler(CourierNotFoundException::class)
    fun handleCourierNotFound(e: CourierNotFoundException): ResponseEntity<ErrorResponse> {
        log.warn("[Delivery] 배송기사 없음 - {}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("COURIER_NOT_FOUND", e.message ?: "배송기사를 찾을 수 없습니다"))
    }

    @ExceptionHandler(InvalidDeliveryOperationException::class)
    fun handleInvalidOperation(e: InvalidDeliveryOperationException): ResponseEntity<ErrorResponse> {
        log.warn("[Delivery] 잘못된 요청 - {}", e.message)
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse("INVALID_DELIVERY_OPERATION", e.message ?: "잘못된 요청입니다"))
    }

    // @Version 충돌: 동시 요청으로 낙관적 락 실패 → 클라이언트 재시도 유도
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLocking(e: OptimisticLockingFailureException): ResponseEntity<ErrorResponse> {
        log.warn("[Delivery] 동시 요청 충돌 - {}", e.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("CONCURRENT_CONFLICT", "동시 요청이 충돌했습니다. 다시 시도해주세요."))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("[Delivery] 처리되지 않은 예외", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다"))
    }
}
