package org.example.productservice

import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import org.example.productservice.controller.ProductController
import org.example.productservice.exception.GlobalExceptionHandler
import org.example.productservice.exception.ProductNotFoundException
import org.example.productservice.exception.ProductStatusConflictException
import org.example.productservice.service.ProductService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(controllers = [ProductController::class])
@Import(GlobalExceptionHandler::class)
class GlobalExceptionHandlerTest {

    @MockkBean
    lateinit var productService: ProductService

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Test
    fun `존재하지 않는 productId 조회 - 404 반환`() {
        coEvery { productService.getProduct("not-exist") } throws ProductNotFoundException("not-exist")

        webTestClient.get()
            .uri("/v1/products/not-exist")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("PRODUCT_NOT_FOUND")
            .jsonPath("$.message").isEqualTo("Product not found. productId=not-exist")
            .jsonPath("$.timestamp").exists()
    }

    @Test
    fun `DISCONTINUED 상품 수정 시도 - 409 반환`() {
        coEvery { productService.updateProduct(eq("prod-001"), any()) } throws
            ProductStatusConflictException("Cannot update a discontinued product. productId=prod-001")

        webTestClient.put()
            .uri("/v1/products/prod-001")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "name":"x","description":"x","category":"x","brand":"x",
                  "basePrice":1000,"imageUrl":"x","tags":[],"isAvailable":false
                }
            """.trimIndent())
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("PRODUCT_STATUS_CONFLICT")
            .jsonPath("$.message").isEqualTo("Cannot update a discontinued product. productId=prod-001")
            .jsonPath("$.timestamp").exists()
    }

    @Test
    fun `DISCONTINUED 상품 상태 변경 시도 - 409 반환`() {
        coEvery { productService.changeStatus(eq("prod-001"), any()) } throws
            ProductStatusConflictException("Cannot change status of a discontinued product. productId=prod-001")

        webTestClient.patch()
            .uri("/v1/products/prod-001/status")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"status":"ON_SALE"}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("PRODUCT_STATUS_CONFLICT")
    }

    @Test
    fun `예상치 못한 서버 오류 - 500 반환`() {
        coEvery { productService.getProduct("error-prod") } throws RuntimeException("DB connection lost")

        webTestClient.get()
            .uri("/v1/products/error-prod")
            .exchange()
            .expectStatus().is5xxServerError
            .expectBody()
            .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
            .jsonPath("$.message").isEqualTo("An unexpected error occurred")
            .jsonPath("$.timestamp").exists()
    }
}
