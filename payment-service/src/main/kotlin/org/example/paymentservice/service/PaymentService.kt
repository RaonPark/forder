package org.example.paymentservice.service

import common.document.payment.PaymentStatus
import common.document.payment.RefundStatus
import kotlinx.coroutines.flow.Flow
import org.example.paymentservice.document.Payment
import org.example.paymentservice.document.PaymentRefund
import org.example.paymentservice.dto.CreatePaymentRequest
import org.example.paymentservice.dto.PaymentResponse
import org.example.paymentservice.dto.RefundRequest
import org.example.paymentservice.dto.RefundResponse
import org.example.paymentservice.dto.toResponse
import org.example.paymentservice.repository.PaymentRefundRepository
import org.example.paymentservice.repository.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val paymentRefundRepository: PaymentRefundRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // ──────────────────────────────────────────
    // CRUD
    // ──────────────────────────────────────────

    @Transactional
    suspend fun createPayment(request: CreatePaymentRequest): PaymentResponse {
        val payment = Payment(
            paymentId  = UUID.randomUUID().toString(),
            orderId    = request.orderId,
            userId     = request.userId,
            totalAmount = request.totalAmount,
            status     = PaymentStatus.REQUESTED,
            method     = request.method,
            pgProvider = request.pgProvider
        )
        return paymentRepository.save(payment).toResponse()
    }

    suspend fun getPayment(paymentId: String): PaymentResponse {
        return paymentRepository.findById(paymentId)?.toResponse()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다 - paymentId=$paymentId")
    }

    suspend fun getPaymentByOrderId(orderId: String): PaymentResponse {
        return paymentRepository.findByOrderId(orderId)?.toResponse()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다 - orderId=$orderId")
    }

    @Transactional
    suspend fun requestRefund(paymentId: String, request: RefundRequest): RefundResponse {
        val payment = paymentRepository.findById(paymentId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다 - paymentId=$paymentId")

        if (payment.status != PaymentStatus.APPROVED) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "승인된 결제만 환불할 수 있습니다 - status=${payment.status}")
        }
        if (request.refundAmount > payment.totalAmount - payment.canceledAmount) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "환불 금액이 결제 금액을 초과합니다")
        }

        val refund = PaymentRefund(
            refundId     = UUID.randomUUID().toString(),
            paymentId    = paymentId,
            orderId      = payment.orderId,
            refundType   = request.refundType,
            refundAmount = request.refundAmount,
            reason       = request.reason,
            status       = RefundStatus.COMPLETED   // 실제 PG 연동 시 PROCESSING → COMPLETED
        )
        paymentRefundRepository.save(refund)

        val updatedPayment = payment.copy(
            status          = PaymentStatus.CANCELED,
            isPartialCanceled = request.refundAmount < payment.totalAmount,
            canceledAmount  = payment.canceledAmount + request.refundAmount
        )
        paymentRepository.save(updatedPayment)

        log.info("[Payment] 환불 완료 - paymentId={}, amount={}", paymentId, request.refundAmount)
        return RefundResponse(
            refundId     = refund.refundId,
            paymentId    = refund.paymentId,
            orderId      = refund.orderId,
            refundType   = refund.refundType,
            refundAmount = refund.refundAmount,
            feeAmount    = refund.feeAmount,
            reason       = refund.reason,
            status       = refund.status,
            createdAt    = refund.createdAt
        )
    }

    fun getRefunds(paymentId: String): Flow<PaymentRefund> =
        paymentRefundRepository.findAllByPaymentId(paymentId)

    // ──────────────────────────────────────────
    // Saga 전용: 결제 처리 / 환불
    // ──────────────────────────────────────────

    @Transactional
    suspend fun processPayment(sagaId: String, orderId: String, userId: String, amount: String): String {
        // 실제 PG 연동 시 이 지점에서 PG API 호출
        // 현재는 결제 생성 + 즉시 승인 처리 (mock)
        val payment = Payment(
            paymentId   = UUID.randomUUID().toString(),
            orderId     = orderId,
            userId      = userId,
            totalAmount = amount.toBigDecimal(),
            status      = PaymentStatus.APPROVED,
            method      = common.document.payment.PaymentMethod.CREDIT_CARD,   // Saga에서 결제수단 추후 확장
            pgProvider  = common.document.payment.PgProvider.TOSS_PAYMENTS,
            pgTid       = "pg-tid-${UUID.randomUUID()}"
        )
        val saved = paymentRepository.save(payment)
        log.info("[Saga] 결제 처리 완료 - sagaId={}, paymentId={}", sagaId, saved.paymentId)
        return saved.paymentId
    }

    @Transactional
    suspend fun refundPayment(sagaId: String, orderId: String, amount: String) {
        val payment = paymentRepository.findByOrderId(orderId)
            ?: run {
                log.warn("[Saga] 환불 대상 결제 없음 - sagaId={}, orderId={}", sagaId, orderId)
                return
            }

        val refund = PaymentRefund(
            refundId     = UUID.randomUUID().toString(),
            paymentId    = payment.paymentId,
            orderId      = orderId,
            refundType   = common.document.payment.RefundType.FULL_CANCELLATION,
            refundAmount = amount.toBigDecimal(),
            status       = RefundStatus.COMPLETED
        )
        paymentRefundRepository.save(refund)
        paymentRepository.save(payment.copy(status = PaymentStatus.CANCELED))
        log.info("[Saga] 환불 완료 - sagaId={}, paymentId={}", sagaId, payment.paymentId)
    }
}
