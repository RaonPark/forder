package org.example.paymentservice.service

import common.document.payment.PaymentMethod
import common.document.payment.PaymentStatus
import common.document.payment.PgProvider
import common.document.payment.RefundType
import kotlinx.coroutines.runBlocking
import org.example.paymentservice.TestcontainersConfiguration
import org.example.paymentservice.document.Payment
import org.example.paymentservice.dto.RefundRequest
import org.example.paymentservice.repository.PaymentRefundRepository
import org.example.paymentservice.repository.PaymentRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ──────────────────────────────────────────────────────────────
// [설계 의도]
//
// 실제 MongoDB(Testcontainers)와 PaymentService를 함께 실행하여
// 단위 테스트(MockK)로는 검증할 수 없는 DB 레벨 동시성 보호를 검증한다.
//
// [왜 NoOpReactiveTransactionManager가 필요한가?]
//   Spring Boot 4.0은 ReactiveMongoTransactionManager를 자동 설정한다.
//   이 매니저가 있으면 @Transactional 메서드가 MongoDB 트랜잭션을 시도하고,
//   Testcontainers의 Standalone MongoDB는 이를 거부한다.
//   ("Transaction numbers are only allowed on a replica set member or mongos")
//
//   동시성 테스트에서 실제로 보호하는 것은 @Version(낙관적 락)과 sagaId unique index
//   이며, 둘 다 MongoDB 드라이버 레벨에서 동작하므로 트랜잭션 없이도 유효하다.
//   따라서 no-op 트랜잭션 매니저로 @Transactional을 무력화해도 테스트 목적에 부합한다.
//
// [동시성 테스트 패턴]
//   CyclicBarrier(N) → N개 스레드가 barrier.await()에서 대기했다가 동시에 출발
//   CountDownLatch   → 모든 스레드 완료를 메인 스레드가 기다림
//   runBlocking      → 각 스레드에서 suspend 함수를 블로킹으로 실행
// ──────────────────────────────────────────────────────────────

/**
 * @Transactional이 MongoDB 트랜잭션을 시작하지 않도록 하는 테스트 전용 설정.
 *
 * - 운영 환경의 ReactiveMongoTransactionManager는 Replica Set이 필요하다.
 * - Testcontainers의 Standalone MongoDB에서 트랜잭션을 시작하면 오류가 발생한다.
 * - no-op 구현으로 @Transactional 경계를 유지하되 실제 트랜잭션은 열지 않는다.
 * - @Version과 unique index는 트랜잭션 외부(드라이버 레벨)에서 동작하므로 테스트 유효성에 영향 없다.
 */
@TestConfiguration
class NoOpTransactionConfig {
    @Bean
    @Primary
    fun testReactiveTransactionManager(): ReactiveTransactionManager =
        object : ReactiveTransactionManager {

            private val noOpTransaction = object : ReactiveTransaction {
                override fun isNewTransaction()  = true
                override fun setRollbackOnly()   {}
                override fun isRollbackOnly()    = false
                override fun isCompleted()       = false
            }

            override fun getReactiveTransaction(definition: TransactionDefinition?): Mono<ReactiveTransaction> =
                Mono.just(noOpTransaction)

            override fun commit(transaction: ReactiveTransaction): Mono<Void>   = Mono.empty()
            override fun rollback(transaction: ReactiveTransaction): Mono<Void> = Mono.empty()
        }
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.kafka.listener.auto-startup=false"]
)
@Import(TestcontainersConfiguration::class, NoOpTransactionConfig::class)
class PaymentServiceIntegrationTest {

    @Autowired private lateinit var paymentService: PaymentService
    @Autowired private lateinit var paymentRepository: PaymentRepository
    @Autowired private lateinit var paymentRefundRepository: PaymentRefundRepository

    @BeforeEach
    fun setUp() = runBlocking {
        paymentRepository.deleteAll()
        paymentRefundRepository.deleteAll()
    }

    // ──────────────────────────────────────────
    // Fixtures
    // ──────────────────────────────────────────

    private fun approvedPayment(
        paymentId: String = "pay-${UUID.randomUUID()}",
        orderId:   String = "order-${UUID.randomUUID()}"
    ) = Payment(
        paymentId   = paymentId,
        orderId     = orderId,
        userId      = "user-1",
        totalAmount = BigDecimal("10000"),
        status      = PaymentStatus.APPROVED,
        method      = PaymentMethod.CREDIT_CARD,
        pgProvider  = PgProvider.TOSS_PAYMENTS
    )

    // ──────────────────────────────────────────
    // processPayment — INSERT 경쟁 (sagaId sparse unique index)
    //
    // [경쟁 시나리오]
    //   ┌─ Thread 1 ─────────────────────────────┐
    //   │ findBySagaId → null                    │
    //   │ (CyclicBarrier 동시 출발)              │
    //   │ Payment(sagaId=X) → save → ✅ 성공      │
    //   └────────────────────────────────────────┘
    //   ┌─ Thread 2~N ───────────────────────────┐
    //   │ findBySagaId → null                    │
    //   │ (CyclicBarrier 동시 출발)              │
    //   │ Payment(sagaId=X) → save               │
    //   │   → 💥 DuplicateKeyException (index)   │
    //   │   → catch → findBySagaId → 기존 Id 반환│
    //   └────────────────────────────────────────┘
    //
    // [기대 결과] 모든 요청 성공 + 동일한 paymentId 반환 + DB 1건
    // ──────────────────────────────────────────

    @Nested
    inner class ProcessPaymentConcurrency {

        @Test
        fun `같은 sagaId로 동시에 10개 요청 - 1건만 저장되고 모두 동일한 paymentId 반환`() {
            val sagaId      = "saga-${UUID.randomUUID()}"
            val concurrency = 10
            val barrier     = CyclicBarrier(concurrency)
            val results     = ConcurrentLinkedQueue<Result<String>>()
            val latch       = CountDownLatch(concurrency)

            repeat(concurrency) {
                Thread {
                    barrier.await()   // 모든 스레드가 여기서 대기했다가 동시에 출발
                    results += runBlocking {
                        runCatching {
                            paymentService.processPayment(sagaId, "order-concurrent", "user-1", "10000")
                        }
                    }
                    latch.countDown()
                }.start()
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS), "테스트 타임아웃")

            val failures = results.filter { it.isFailure }
            assertTrue(
                failures.isEmpty(),
                "모두 성공해야 함. 실패: ${failures.map { it.exceptionOrNull()?.message }}"
            )

            val distinctIds = results.map { it.getOrThrow() }.distinct()
            assertEquals(1, distinctIds.size, "모든 요청이 동일한 paymentId를 반환해야 함")

            val saved = runBlocking { paymentRepository.findBySagaId(sagaId) }
            assertNotNull(saved, "sagaId로 저장된 결제가 존재해야 함")
            assertEquals(1L, runBlocking { paymentRepository.count() }, "payments 컬렉션에 1건만 존재해야 함")
        }
    }

    // ──────────────────────────────────────────
    // requestRefund — UPDATE 경쟁 (@Version 낙관적 락)
    //
    // [경쟁 시나리오]
    //   ┌─ Thread A ────────────────────────────┐
    //   │ findById → payment(version=0)         │
    //   │ save(PaymentRefund A) ✅               │
    //   │ save(payment.copy, ver=0) → ver 0→1 ✅│
    //   └───────────────────────────────────────┘
    //   ┌─ Thread B ────────────────────────────┐
    //   │ findById → payment(version=0)         │ ← 같은 버전을 읽음
    //   │ save(PaymentRefund B) ✅               │
    //   │ save(payment.copy, ver=0)             │
    //   │   → 💥 OLFE (DB는 이미 version=1)     │
    //   └───────────────────────────────────────┘
    //
    // [기대 결과] 1개 성공, 1개 OptimisticLockingFailureException
    // ──────────────────────────────────────────

    @Nested
    inner class RequestRefundConcurrency {

        @Test
        fun `APPROVED 결제에 동시에 2개 환불 요청 - 1개 성공, 1개 OptimisticLockingFailureException`() {
            val paymentId = "pay-${UUID.randomUUID()}"
            runBlocking { paymentRepository.save(approvedPayment(paymentId = paymentId)) }

            val barrier = CyclicBarrier(2)
            val results = ConcurrentLinkedQueue<Result<*>>()
            val latch   = CountDownLatch(2)

            repeat(2) {
                Thread {
                    barrier.await()
                    results += runBlocking {
                        runCatching {
                            paymentService.requestRefund(
                                paymentId,
                                RefundRequest(
                                    refundType   = RefundType.FULL_CANCELLATION,
                                    refundAmount = BigDecimal("10000")
                                )
                            )
                        }
                    }
                    latch.countDown()
                }.start()
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS), "테스트 타임아웃")

            val successes = results.filter { it.isSuccess }
            val failures  = results.filter { it.isFailure }
            assertEquals(1, successes.size, "정확히 하나만 성공해야 함")
            assertEquals(1, failures.size,  "나머지 하나는 실패해야 함")
            assertIs<OptimisticLockingFailureException>(failures.first().exceptionOrNull())

            val updated = runBlocking { paymentRepository.findById(paymentId) }
            assertEquals(PaymentStatus.CANCELED, updated?.status)
            assertEquals(BigDecimal("10000"),     updated?.canceledAmount)
        }
    }

    // ──────────────────────────────────────────
    // refundPayment (Saga) — sagaId 멱등성
    //
    // [경쟁 시나리오]
    //   ┌─ Thread 1~N (모두 동시 출발) ─────────┐
    //   │ existsBySagaId → false (모두)          │ ← 아직 아무것도 저장 안 됨
    //   │ PaymentRefund(sagaId=X) → save        │
    //   │   Thread 1: ✅ 저장 성공              │
    //   │   Thread 2~N: 💥 DuplicateKeyException│ ← sagaId unique index
    //   │              → catch → early return   │
    //   │ Thread 1: payment → save(CANCELED) ✅  │
    //   └───────────────────────────────────────┘
    //
    // [기대 결과] 모두 예외 없이 완료 + 환불 1건 + 결제 CANCELED
    // ──────────────────────────────────────────

    @Nested
    inner class RefundPaymentConcurrency {

        @Test
        fun `같은 sagaId로 동시에 10개 환불 요청 - 1건만 저장되고 모두 예외 없이 완료`() {
            val orderId = "order-${UUID.randomUUID()}"
            val sagaId  = "saga-refund-${UUID.randomUUID()}"
            runBlocking { paymentRepository.save(approvedPayment(orderId = orderId)) }

            val concurrency = 10
            val barrier     = CyclicBarrier(concurrency)
            val results     = ConcurrentLinkedQueue<Result<*>>()
            val latch       = CountDownLatch(concurrency)

            repeat(concurrency) {
                Thread {
                    barrier.await()
                    results += runBlocking {
                        runCatching { paymentService.refundPayment(sagaId, orderId, "10000") }
                    }
                    latch.countDown()
                }.start()
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS), "테스트 타임아웃")

            val failures = results.filter { it.isFailure }
            assertTrue(
                failures.isEmpty(),
                "모두 성공해야 함. 실패: ${failures.map { it.exceptionOrNull()?.message }}"
            )

            val refundCount = runBlocking { paymentRefundRepository.count() }
            assertEquals(1L, refundCount, "환불은 1건만 저장되어야 함")

            val updated = runBlocking { paymentRepository.findByOrderId(orderId) }
            assertEquals(PaymentStatus.CANCELED, updated?.status)
        }
    }
}
