package org.example.orderservice

import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import org.example.orderservice.controller.OrderController
import org.example.orderservice.exception.GlobalExceptionHandler
import org.example.orderservice.exception.OrderNotFoundException
import org.example.orderservice.exception.OrderStatusConflictException
import org.example.orderservice.service.OrderService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * GlobalExceptionHandler 슬라이스 테스트.
 *
 * @WebFluxTest: Controller + ControllerAdvice 레이어만 로드 (MongoDB, Kafka 불필요)
 * OrderService 는 @MockkBean 으로 대체하여 예외 시나리오를 제어한다.
 *
 * 검증 항목:
 *   1. OrderNotFoundException     → 404 + code=ORDER_NOT_FOUND
 *   2. OrderStatusConflictException → 409 + code=ORDER_STATUS_CONFLICT
 *   3. 예상치 못한 Exception       → 500 + code=INTERNAL_ERROR
 *   4. ErrorResponse 공통 필드(code, message, timestamp) 존재 여부
 */
@WebFluxTest(controllers = [OrderController::class])
@Import(GlobalExceptionHandler::class)
class GlobalExceptionHandlerTest {

    @MockkBean
    lateinit var orderService: OrderService

    @Autowired
    lateinit var webTestClient: WebTestClient

    // ════════════════════════════════════════════════════════════════════════
    // 404 — OrderNotFoundException
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `존재하지 않는 orderId 조회 - 404 반환`() {
        coEvery { orderService.getOrder("not-exist") } throws OrderNotFoundException("not-exist")

        webTestClient.get()
            .uri("/v1/orders/not-exist")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("ORDER_NOT_FOUND")
            .jsonPath("$.message").isEqualTo("Order not found. orderId=not-exist")
            .jsonPath("$.timestamp").exists()
    }

    // ════════════════════════════════════════════════════════════════════════
    // 409 — OrderStatusConflictException
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `취소 불가 상태 주문 취소 시도 - 409 반환`() {
        coEvery { orderService.cancelOrder(eq("order-001"), any()) } throws
            OrderStatusConflictException("Cannot cancel order in status PENDING. orderId=order-001")

        webTestClient.patch()
            .uri("/v1/orders/order-001/cancel")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"reason": "고객 변심"}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("ORDER_STATUS_CONFLICT")
            .jsonPath("$.message").isEqualTo("Cannot cancel order in status PENDING. orderId=order-001")
            .jsonPath("$.timestamp").exists()
    }

    @Test
    fun `반품 불가 상태 반품 신청 - 409 반환`() {
        coEvery { orderService.requestReturn(eq("order-001"), any()) } throws
            OrderStatusConflictException("Cannot request return for order in status PREPARING. orderId=order-001")

        webTestClient.post()
            .uri("/v1/orders/order-001/returns")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"reason": "상품 불량"}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("ORDER_STATUS_CONFLICT")
            .jsonPath("$.message").isEqualTo("Cannot request return for order in status PREPARING. orderId=order-001")
    }

    // ════════════════════════════════════════════════════════════════════════
    // 500 — 예상치 못한 Exception
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `예상치 못한 서버 오류 - 500 반환`() {
        coEvery { orderService.getOrder("error-order") } throws RuntimeException("DB connection lost")

        webTestClient.get()
            .uri("/v1/orders/error-order")
            .exchange()
            .expectStatus().is5xxServerError
            .expectBody()
            .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
            .jsonPath("$.message").isEqualTo("An unexpected error occurred")
            .jsonPath("$.timestamp").exists()
    }

    // ════════════════════════════════════════════════════════════════════════
    // ErrorResponse 구조 검증
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `ErrorResponse 는 code, message, timestamp 필드를 모두 포함한다`() {
        coEvery { orderService.getOrder(any()) } throws OrderNotFoundException("any-id")

        webTestClient.get()
            .uri("/v1/orders/any-id")
            .exchange()
            .expectBody()
            .jsonPath("$.code").exists()
            .jsonPath("$.message").exists()
            .jsonPath("$.timestamp").exists()
    }
}
