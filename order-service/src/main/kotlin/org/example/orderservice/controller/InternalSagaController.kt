package org.example.orderservice.controller

import org.example.orderservice.dto.TimeoutProcessResult
import org.example.orderservice.service.SagaTimeoutService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Airflow DAG 에서 호출하는 내부 전용 엔드포인트.
 *
 * 보안 참고: `/internal/**/` 경로는 Spring Cloud Gateway 에서 외부에 노출하지 않는다.
 * 클러스터 내부(VPN) 에서만 직접 포트(9001)로 접근 가능.
 *
 * 멱등성: processStuckOrders() 는 이미 terminal 상태인 주문을 건너뛰므로
 * Airflow 가 동일 DAG 를 중복 실행해도 안전하다.
 */
@RestController
@RequestMapping("/internal/saga")
class InternalSagaController(
    private val sagaTimeoutService: SagaTimeoutService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 멈춘 Saga 를 탐지하고 보상 처리를 실행한다.
     *
     * Airflow DAG 에서 30분 주기로 호출한다.
     * DAG 예시는 infrastructure/airflow/dags/saga_timeout_dag.py 를 참고한다.
     */
    @PostMapping("/timeout/process")
    suspend fun processTimeouts(): ResponseEntity<TimeoutProcessResult> {
        log.info("[Internal] Saga timeout process triggered by Airflow")
        val result = sagaTimeoutService.processStuckOrders()
        return ResponseEntity.ok(result)
    }
}