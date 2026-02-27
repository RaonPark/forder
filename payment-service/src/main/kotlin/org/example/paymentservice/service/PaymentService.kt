package org.example.paymentservice.service

import common.document.payment.PaymentMethod
import common.document.payment.PaymentStatus
import common.document.payment.PgProvider
import common.document.payment.RefundStatus
import common.document.payment.RefundType
import kotlinx.coroutines.flow.Flow
import org.example.paymentservice.document.Payment
import org.example.paymentservice.document.PaymentRefund
import org.example.paymentservice.dto.CreatePaymentRequest
import org.example.paymentservice.dto.PaymentResponse
import org.example.paymentservice.dto.RefundRequest
import org.example.paymentservice.dto.RefundResponse
import org.example.paymentservice.dto.toResponse
import org.example.paymentservice.exception.InvalidPaymentOperationException
import org.example.paymentservice.exception.PaymentNotFoundException
import org.example.paymentservice.repository.PaymentRefundRepository
import org.example.paymentservice.repository.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
            paymentId   = UUID.randomUUID().toString(),
            orderId     = request.orderId,
            userId      = request.userId,
            totalAmount = request.totalAmount,
            status      = PaymentStatus.REQUESTED,
            method      = request.method,
            pgProvider  = request.pgProvider
        )
        return paymentRepository.save(payment).toResponse()
    }

    suspend fun getPayment(paymentId: String): PaymentResponse {
        return paymentRepository.findById(paymentId)?.toResponse()
            ?: throw PaymentNotFoundException("결제를 찾을 수 없습니다 - paymentId=$paymentId")
    }

    suspend fun getPaymentByOrderId(orderId: String): PaymentResponse {
        return paymentRepository.findByOrderId(orderId)?.toResponse()
            ?: throw PaymentNotFoundException("결제를 찾을 수 없습니다 - orderId=$orderId")
    }

    // OptimisticLockingFailureException은 잡지 않고 전파 → GlobalExceptionHandler가 409로 변환
    @Transactional
    suspend fun requestRefund(paymentId: String, request: RefundRequest): RefundResponse {
        val payment = paymentRepository.findById(paymentId)
            ?: throw PaymentNotFoundException("결제를 찾을 수 없습니다 - paymentId=$paymentId")

        if (payment.status != PaymentStatus.APPROVED)
            throw InvalidPaymentOperationException("승인된 결제만 환불할 수 있습니다 - status=${payment.status}")

        if (request.refundAmount > payment.totalAmount - payment.canceledAmount)
            throw InvalidPaymentOperationException("환불 금액이 결제 금액을 초과합니다")

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
            status            = PaymentStatus.CANCELED,
            isPartialCanceled = request.refundAmount < payment.totalAmount,
            canceledAmount    = payment.canceledAmount + request.refundAmount
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
        // 1차 방어: 애플리케이션 레벨 중복 체크 (빠른 경로)
        paymentRepository.findBySagaId(sagaId)?.let {
            log.info("[Saga] 이미 처리된 결제 - sagaId={}, paymentId={}", sagaId, it.paymentId)
            return it.paymentId
        }

        return try {
            val payment = Payment(
                paymentId   = UUID.randomUUID().toString(),
                sagaId      = sagaId,
                orderId     = orderId,
                userId      = userId,
                totalAmount = amount.toBigDecimal(),
                status      = PaymentStatus.APPROVED,
                method      = PaymentMethod.CREDIT_CARD,
                pgProvider  = PgProvider.TOSS_PAYMENTS,
                pgTid       = "pg-tid-${UUID.randomUUID()}"
            )
            val saved = paymentRepository.save(payment)
            log.info("[Saga] 결제 처리 완료 - sagaId={}, paymentId={}", sagaId, saved.paymentId)
            saved.paymentId
        } catch (e: DuplicateKeyException) {
            // 2차 방어: 분산 환경 동시 처리로 unique index 위반 → 이미 저장된 결제 반환
            log.warn("[Saga] sagaId 중복 감지(동시 처리) - sagaId={}", sagaId)
            paymentRepository.findBySagaId(sagaId)?.paymentId
                ?: throw e
        }
    }

    @Transactional
    suspend fun refundPayment(sagaId: String, orderId: String, amount: String) {
        val payment = paymentRepository.findByOrderId(orderId)
            ?: run {
                log.warn("[Saga] 환불 대상 결제 없음 - sagaId={}, orderId={}", sagaId, orderId)
                return
            }

        // 이미 처리된 sagaId 확인 (Kafka at-least-once 재전달 대비)
        if (paymentRefundRepository.existsBySagaId(sagaId)) {
            log.info("[Saga] 이미 처리된 환불 - sagaId={}", sagaId)
            return
        }

        val refund = PaymentRefund(
            refundId     = UUID.randomUUID().toString(),
            sagaId       = sagaId,
            paymentId    = payment.paymentId,
            orderId      = orderId,
            refundType   = RefundType.FULL_CANCELLATION,
            refundAmount = amount.toBigDecimal(),
            status       = RefundStatus.COMPLETED
        )

        try {
            paymentRefundRepository.save(refund)
        } catch (e: DuplicateKeyException) {
            // 2차 방어: 동시 처리로 sagaId unique index 위반 → 이미 저장된 환불 (멱등성)
            log.warn("[Saga] sagaId 중복 환불 감지(동시 처리) - sagaId={}", sagaId)
            return
        }

        paymentRepository.save(payment.copy(status = PaymentStatus.CANCELED))
        log.info("[Saga] 환불 완료 - sagaId={}, paymentId={}", sagaId, payment.paymentId)
    }
}
