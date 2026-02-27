package org.example.paymentservice.service

import common.document.payment.PaymentMethod
import common.document.payment.PaymentStatus
import common.document.payment.PgProvider
import common.document.payment.RefundStatus
import common.document.payment.RefundType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.example.paymentservice.document.Payment
import org.example.paymentservice.document.PaymentRefund
import org.example.paymentservice.dto.CreatePaymentRequest
import org.example.paymentservice.dto.RefundRequest
import org.example.paymentservice.exception.InvalidPaymentOperationException
import org.example.paymentservice.exception.PaymentNotFoundException
import org.example.paymentservice.repository.PaymentRefundRepository
import org.example.paymentservice.repository.PaymentRepository
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExtendWith(MockKExtension::class)
class PaymentServiceTest {

    @MockK lateinit var paymentRepository: PaymentRepository
    @MockK lateinit var paymentRefundRepository: PaymentRefundRepository
    @InjectMockKs lateinit var paymentService: PaymentService

    // ──────────────────────────────────────────
    // Fixtures
    // ──────────────────────────────────────────

    private fun payment(
        paymentId: String = "pay-1",
        orderId: String = "order-1",
        userId: String = "user-1",
        totalAmount: BigDecimal = BigDecimal("10000"),
        status: PaymentStatus = PaymentStatus.APPROVED,
        canceledAmount: BigDecimal = BigDecimal.ZERO
    ) = Payment(
        paymentId      = paymentId,
        orderId        = orderId,
        userId         = userId,
        totalAmount    = totalAmount,
        status         = status,
        method         = PaymentMethod.CREDIT_CARD,
        pgProvider     = PgProvider.TOSS_PAYMENTS,
        canceledAmount = canceledAmount
    )

    private fun refund(
        refundId: String = "refund-1",
        paymentId: String = "pay-1",
        orderId: String = "order-1",
        refundAmount: BigDecimal = BigDecimal("10000")
    ) = PaymentRefund(
        refundId     = refundId,
        paymentId    = paymentId,
        orderId      = orderId,
        refundType   = RefundType.FULL_CANCELLATION,
        refundAmount = refundAmount,
        status       = RefundStatus.COMPLETED
    )

    private fun createRequest(
        orderId: String = "order-1",
        userId: String = "user-1",
        totalAmount: BigDecimal = BigDecimal("10000")
    ) = CreatePaymentRequest(
        orderId     = orderId,
        userId      = userId,
        totalAmount = totalAmount,
        method      = PaymentMethod.CREDIT_CARD,
        pgProvider  = PgProvider.TOSS_PAYMENTS
    )

    private fun refundRequest(
        refundAmount: BigDecimal = BigDecimal("10000"),
        refundType: RefundType = RefundType.FULL_CANCELLATION
    ) = RefundRequest(
        refundType   = refundType,
        refundAmount = refundAmount
    )

    // ──────────────────────────────────────────
    // createPayment
    // ──────────────────────────────────────────

    @Nested
    inner class CreatePayment {

        @Test
        fun `결제 생성 성공 - REQUESTED 상태로 저장`() = runTest {
            val req = createRequest()
            val captured = slot<Payment>()
            coEvery { paymentRepository.save(capture(captured)) } returns payment(status = PaymentStatus.REQUESTED)

            val result = paymentService.createPayment(req)

            with(captured.captured) {
                assertEquals(PaymentStatus.REQUESTED, status)
                assertEquals(req.orderId, orderId)
                assertEquals(req.userId, userId)
                assertEquals(req.totalAmount, totalAmount)
                assertEquals(req.method, method)
                assertEquals(req.pgProvider, pgProvider)
                assertTrue(paymentId.isNotBlank())
            }
            assertEquals(PaymentStatus.REQUESTED, result.status)
        }
    }

    // ──────────────────────────────────────────
    // getPayment
    // ──────────────────────────────────────────

    @Nested
    inner class GetPayment {

        @Test
        fun `paymentId로 결제 조회 성공`() = runTest {
            coEvery { paymentRepository.findById("pay-1") } returns payment()

            val result = paymentService.getPayment("pay-1")

            assertEquals("pay-1", result.paymentId)
            assertEquals(PaymentStatus.APPROVED, result.status)
        }

        @Test
        fun `존재하지 않는 paymentId 조회 시 PaymentNotFoundException 발생`() = runTest {
            coEvery { paymentRepository.findById("pay-none") } returns null

            val ex = runCatching { paymentService.getPayment("pay-none") }.exceptionOrNull()

            assertIs<PaymentNotFoundException>(ex)
        }
    }

    // ──────────────────────────────────────────
    // getPaymentByOrderId
    // ──────────────────────────────────────────

    @Nested
    inner class GetPaymentByOrderId {

        @Test
        fun `orderId로 결제 조회 성공`() = runTest {
            coEvery { paymentRepository.findByOrderId("order-1") } returns payment()

            val result = paymentService.getPaymentByOrderId("order-1")

            assertEquals("order-1", result.orderId)
        }

        @Test
        fun `존재하지 않는 orderId 조회 시 PaymentNotFoundException 발생`() = runTest {
            coEvery { paymentRepository.findByOrderId("order-none") } returns null

            val ex = runCatching { paymentService.getPaymentByOrderId("order-none") }.exceptionOrNull()

            assertIs<PaymentNotFoundException>(ex)
        }
    }

    // ──────────────────────────────────────────
    // requestRefund
    // ──────────────────────────────────────────

    @Nested
    inner class RequestRefund {

        @Test
        fun `전체 환불 성공 - CANCELED 상태, isPartialCanceled=false`() = runTest {
            val base = payment(totalAmount = BigDecimal("10000"))
            val capturedPayment = slot<Payment>()
            coEvery { paymentRepository.findById("pay-1") } returns base
            coEvery { paymentRefundRepository.save(any()) } returns refund()
            coEvery { paymentRepository.save(capture(capturedPayment)) } returns base.copy(status = PaymentStatus.CANCELED)

            val result = paymentService.requestRefund("pay-1", refundRequest(BigDecimal("10000")))

            with(capturedPayment.captured) {
                assertEquals(PaymentStatus.CANCELED, status)
                assertFalse(isPartialCanceled)
                assertEquals(BigDecimal("10000"), canceledAmount)
            }
            assertEquals(RefundStatus.COMPLETED, result.status)
            assertEquals(BigDecimal("10000"), result.refundAmount)
        }

        @Test
        fun `부분 환불 성공 - CANCELED 상태, isPartialCanceled=true, canceledAmount 누적`() = runTest {
            val base = payment(totalAmount = BigDecimal("10000"))
            val capturedPayment = slot<Payment>()
            coEvery { paymentRepository.findById("pay-1") } returns base
            coEvery { paymentRefundRepository.save(any()) } returns refund(refundAmount = BigDecimal("3000"))
            coEvery { paymentRepository.save(capture(capturedPayment)) } returns base

            paymentService.requestRefund("pay-1", refundRequest(BigDecimal("3000"), RefundType.PARTIAL_CANCELLATION))

            with(capturedPayment.captured) {
                assertEquals(PaymentStatus.CANCELED, status)
                assertTrue(isPartialCanceled)
                assertEquals(BigDecimal("3000"), canceledAmount)
            }
        }

        @Test
        fun `이미 취소된 잔여 금액에 대한 부분 환불 - canceledAmount 누적`() = runTest {
            val base = payment(totalAmount = BigDecimal("10000"), canceledAmount = BigDecimal("3000"))
            val capturedPayment = slot<Payment>()
            coEvery { paymentRepository.findById("pay-1") } returns base
            coEvery { paymentRefundRepository.save(any()) } returns refund(refundAmount = BigDecimal("4000"))
            coEvery { paymentRepository.save(capture(capturedPayment)) } returns base

            paymentService.requestRefund("pay-1", refundRequest(BigDecimal("4000"), RefundType.PARTIAL_CANCELLATION))

            assertEquals(BigDecimal("7000"), capturedPayment.captured.canceledAmount)
        }

        @Test
        fun `존재하지 않는 결제 환불 시 PaymentNotFoundException 발생`() = runTest {
            coEvery { paymentRepository.findById("pay-none") } returns null

            val ex = runCatching {
                paymentService.requestRefund("pay-none", refundRequest())
            }.exceptionOrNull()

            assertIs<PaymentNotFoundException>(ex)
        }

        @Test
        fun `APPROVED 아닌 결제 환불 시도 시 InvalidPaymentOperationException 발생`() = runTest {
            coEvery { paymentRepository.findById("pay-1") } returns payment(status = PaymentStatus.CANCELED)

            val ex = runCatching {
                paymentService.requestRefund("pay-1", refundRequest())
            }.exceptionOrNull()

            assertIs<InvalidPaymentOperationException>(ex)
        }

        @Test
        fun `환불 금액 초과 시 InvalidPaymentOperationException 발생`() = runTest {
            val base = payment(totalAmount = BigDecimal("10000"), canceledAmount = BigDecimal("3000"))
            coEvery { paymentRepository.findById("pay-1") } returns base

            val ex = runCatching {
                paymentService.requestRefund("pay-1", refundRequest(BigDecimal("8000")))
            }.exceptionOrNull()

            assertIs<InvalidPaymentOperationException>(ex)
        }

        @Test
        fun `동시 요청 충돌(@Version) 시 OptimisticLockingFailureException 전파 - HTTP 변환은 GlobalExceptionHandler 담당`() = runTest {
            val base = payment()
            coEvery { paymentRepository.findById("pay-1") } returns base
            coEvery { paymentRefundRepository.save(any()) } returns refund()
            coEvery { paymentRepository.save(any()) } throws OptimisticLockingFailureException("version conflict")

            val ex = runCatching {
                paymentService.requestRefund("pay-1", refundRequest())
            }.exceptionOrNull()

            assertIs<OptimisticLockingFailureException>(ex)
        }
    }

    // ──────────────────────────────────────────
    // getRefunds
    // ──────────────────────────────────────────

    @Nested
    inner class GetRefunds {

        @Test
        fun `환불 목록 조회 성공`() = runTest {
            val refunds = listOf(refund("r-1"), refund("r-2"))
            every { paymentRefundRepository.findAllByPaymentId("pay-1") } returns flowOf(*refunds.toTypedArray())

            val result = paymentService.getRefunds("pay-1").toList()

            assertEquals(2, result.size)
            assertEquals("r-1", result[0].refundId)
            assertEquals("r-2", result[1].refundId)
        }
    }

    // ──────────────────────────────────────────
    // processPayment (Saga)
    // ──────────────────────────────────────────

    @Nested
    inner class ProcessPayment {

        @Test
        fun `결제 처리 성공 - sagaId 포함 APPROVED 상태로 저장하고 paymentId 반환`() = runTest {
            val captured = slot<Payment>()
            val savedPayment = payment(paymentId = "pay-new", status = PaymentStatus.APPROVED)
            coEvery { paymentRepository.findBySagaId("saga-1") } returns null
            coEvery { paymentRepository.save(capture(captured)) } returns savedPayment

            val result = paymentService.processPayment("saga-1", "order-1", "user-1", "10000")

            with(captured.captured) {
                assertEquals(PaymentStatus.APPROVED, status)
                assertEquals("saga-1", sagaId)
                assertEquals("order-1", orderId)
                assertEquals("user-1", userId)
                assertEquals(BigDecimal("10000"), totalAmount)
                assertNotNull(pgTid)
            }
            assertEquals("pay-new", result)
        }

        @Test
        fun `이미 처리된 sagaId 수신 시 기존 paymentId 반환 (멱등성)`() = runTest {
            coEvery { paymentRepository.findBySagaId("saga-dup") } returns payment(paymentId = "pay-existing")

            val result = paymentService.processPayment("saga-dup", "order-1", "user-1", "10000")

            assertEquals("pay-existing", result)
            coVerify(exactly = 0) { paymentRepository.save(any()) }
        }

        @Test
        fun `동시 처리로 DuplicateKeyException 발생 시 기존 결제 paymentId 반환`() = runTest {
            coEvery { paymentRepository.findBySagaId("saga-race") } returnsMany listOf(null, payment(paymentId = "pay-race"))
            coEvery { paymentRepository.save(any()) } throws DuplicateKeyException("sagaId duplicate")

            val result = paymentService.processPayment("saga-race", "order-1", "user-1", "10000")

            assertEquals("pay-race", result)
        }
    }

    // ──────────────────────────────────────────
    // refundPayment (Saga)
    // ──────────────────────────────────────────

    @Nested
    inner class RefundPayment {

        @Test
        fun `환불 처리 성공 - sagaId 포함 refund 저장 및 payment CANCELED 상태 업데이트`() = runTest {
            val base = payment()
            val capturedPayment = slot<Payment>()
            val capturedRefund = slot<PaymentRefund>()
            coEvery { paymentRepository.findByOrderId("order-1") } returns base
            coEvery { paymentRefundRepository.existsBySagaId("saga-1") } returns false
            coEvery { paymentRefundRepository.save(capture(capturedRefund)) } returns refund()
            coEvery { paymentRepository.save(capture(capturedPayment)) } returns base.copy(status = PaymentStatus.CANCELED)

            paymentService.refundPayment("saga-1", "order-1", "10000")

            coVerifyOrder {
                paymentRefundRepository.save(any())
                paymentRepository.save(any())
            }
            assertEquals(PaymentStatus.CANCELED, capturedPayment.captured.status)
            assertEquals("saga-1", capturedRefund.captured.sagaId)
        }

        @Test
        fun `이미 처리된 sagaId 수신 시 early return (멱등성)`() = runTest {
            coEvery { paymentRepository.findByOrderId("order-1") } returns payment()
            coEvery { paymentRefundRepository.existsBySagaId("saga-dup") } returns true

            paymentService.refundPayment("saga-dup", "order-1", "10000")

            coVerify(exactly = 0) { paymentRefundRepository.save(any()) }
            coVerify(exactly = 0) { paymentRepository.save(any()) }
        }

        @Test
        fun `환불 대상 결제 없으면 예외 없이 early return`() = runTest {
            coEvery { paymentRepository.findByOrderId("order-none") } returns null

            paymentService.refundPayment("saga-1", "order-none", "10000")

            coVerify(exactly = 0) { paymentRefundRepository.save(any()) }
            coVerify(exactly = 0) { paymentRepository.save(any()) }
        }

        @Test
        fun `동시 처리로 DuplicateKeyException 발생 시 early return (멱등성)`() = runTest {
            coEvery { paymentRepository.findByOrderId("order-1") } returns payment()
            coEvery { paymentRefundRepository.existsBySagaId("saga-dup") } returns false
            coEvery { paymentRefundRepository.save(any()) } throws DuplicateKeyException("sagaId duplicate")

            paymentService.refundPayment("saga-dup", "order-1", "10000")

            coVerify(exactly = 0) { paymentRepository.save(any()) }
        }
    }
}
