"""
saga_timeout_dag.py
===================
멈춘 Saga 를 주기적으로 탐지하고 보상 트랜잭션을 실행하는 Airflow DAG.

실행 흐름:
  1. order-service 헬스 체크 (서비스 다운 상태에서 처리 방지)
  2. POST /internal/saga/timeout/process 호출 → 멈춘 주문 탐지 및 보상
  3. 응답 분석:
     - processedCount > 0 → 경고 로그 (근본 원인 조사 필요)
     - skippedOrders 비어있지 않음 → COMPENSATING 교착 상태 → 즉시 알림 및 DAG 실패

Airflow 3.x 사전 설정 (UI > Admin):
  [Connections]
    Conn Id  : order_service
    Conn Type: HTTP
    Host     : http://order-service  (클러스터 내부 서비스명)
    Port     : 9001

  [Variables]
    SAGA_ALERT_EMAIL : platform-team@company.com
"""
from __future__ import annotations

import json
import logging
from datetime import datetime, timedelta
from typing import Any

from airflow import DAG
from airflow.models import Variable
from airflow.operators.python import PythonOperator
from airflow.providers.http.operators.http import HttpOperator
from airflow.providers.http.sensors.http import HttpSensor

logger = logging.getLogger(__name__)

# ── 설정 ──────────────────────────────────────────────────────────────────────

ORDER_SERVICE_CONN_ID = "order_service"
ALERT_EMAIL = Variable.get("SAGA_ALERT_EMAIL", default_var="platform-team@company.com")

default_args = {
    "owner": "platform-team",
    "depends_on_past": False,
    "email": [ALERT_EMAIL],
    "email_on_failure": True,
    "email_on_retry": False,
    # ExponentialBackoff 재시도: 2분 → 4분 → 8분 (최대 10분)
    "retries": 3,
    "retry_delay": timedelta(minutes=2),
    "retry_exponential_backoff": True,
    "max_retry_delay": timedelta(minutes=10),
}


# ── 콜백 ──────────────────────────────────────────────────────────────────────

def on_dag_failure(context: dict[str, Any]) -> None:
    """DAG 레벨 실패 시 구조화된 로그 출력. 필요 시 PagerDuty / Slack 연동 가능."""
    dag_id    = context.get("dag_run").dag_id
    task_id   = context.get("task_instance").task_id
    exception = context.get("exception")
    run_id    = context.get("run_id")

    logger.error(
        "[ALERT] Saga timeout DAG failed | dag=%s | task=%s | run=%s | error=%s",
        dag_id, task_id, run_id, str(exception),
    )

    # Slack / PagerDuty 연동 예시:
    # slack_client.chat_postMessage(channel="#alerts", text=f"...")


# ── Task 함수 ─────────────────────────────────────────────────────────────────

def analyze_timeout_result(**context: Any) -> None:
    """
    process_stuck_sagas 의 JSON 응답을 파싱하고 이상 상태를 탐지한다.

    정상 : processedCount == 0  → 모든 Saga 정상 처리 중
    경고 : processedCount > 0   → 멈춘 Saga 보상 완료. 업스트림 서비스 지연 여부 조사.
    심각 : skippedOrders 비어있지 않음
           → COMPENSATING 교착 상태. 자동 복구 불가. 즉시 수동 개입 필요.
    """
    response_text: str = context["ti"].xcom_pull(task_ids="process_stuck_sagas")

    if not response_text:
        logger.warning("[SagaTimeout] Empty response from order-service — skipping analysis")
        return

    result: dict = json.loads(response_text)

    processed_count     = result.get("processedCount", 0)
    cancelled_orders    = result.get("cancelledOrders", [])
    cancellation_failed = result.get("cancellationFailedOrders", [])
    return_failed       = result.get("returnFailedOrders", [])
    skipped_orders      = result.get("skippedOrders", [])

    logger.info(
        "[SagaTimeout] Scan complete | processed=%d | cancelled=%s | "
        "cancellationFailed=%s | returnFailed=%s | skipped=%s",
        processed_count, cancelled_orders,
        cancellation_failed, return_failed, skipped_orders,
    )

    # 경고: 멈춘 Saga 가 있었음 → 업스트림 서비스 이상 가능성
    if processed_count > 0:
        logger.warning(
            "[SagaTimeout] ⚠️ %d stuck saga(s) were auto-compensated. "
            "Investigate inventory/payment/delivery service for abnormal latency.",
            processed_count,
        )

    # 심각: COMPENSATING 교착 → DAG 실패 처리로 on_failure_callback + 이메일 트리거
    if skipped_orders:
        msg = (
            f"[CRITICAL] {len(skipped_orders)} order(s) are stuck in COMPENSATING state "
            f"and require IMMEDIATE manual intervention.\n"
            f"Order IDs: {skipped_orders}\n\n"
            f"Action checklist:\n"
            f"  1. Check order-service logs for each order ID\n"
            f"  2. Verify inventory / payment service status\n"
            f"  3. Confirm whether compensation commands were consumed\n"
            f"  4. If safe, manually re-trigger via support API or replay from DLT"
        )
        logger.error(msg)
        raise RuntimeError(msg)  # DAG 실패 → 이메일 + on_failure_callback 트리거


# ── DAG 정의 ──────────────────────────────────────────────────────────────────

with DAG(
    dag_id="saga_timeout_processor",
    default_args=default_args,
    description="멈춘 Saga 탐지 및 보상 트랜잭션 실행. COMPENSATING 교착 시 즉시 알림.",
    schedule=timedelta(minutes=30),            # Airflow 3.x: schedule_interval → schedule
    start_date=datetime(2025, 1, 1),
    catchup=False,
    max_active_runs=1,       # 동시 실행 방지: 멱등하지만 중복 실행 불필요
    tags=["saga", "order-service", "compensation", "critical"],
    on_failure_callback=on_dag_failure,
) as dag:

    # Task 1: order-service 헬스 체크
    # 서비스가 다운된 상태에서 처리를 시작하지 않도록 방지
    health_check = HttpSensor(
        task_id="order_service_health_check",
        http_conn_id=ORDER_SERVICE_CONN_ID,
        endpoint="/actuator/health",
        method="GET",
        response_check=lambda r: r.json().get("status") == "UP",
        poke_interval=30,       # 30초마다 재확인
        timeout=120,            # 최대 2분 대기 후 실패
        mode="reschedule",      # Worker 슬롯 절약 (blocking 대신 reschedule)
    )

    # Task 2: 멈춘 Saga 탐지 및 보상 실행
    # 멱등성: 이미 terminal 상태인 주문은 OrderRepository 쿼리에서 제외됨
    process_timeouts = HttpOperator(
        task_id="process_stuck_sagas",
        http_conn_id=ORDER_SERVICE_CONN_ID,
        endpoint="/internal/saga/timeout/process",
        method="POST",
        headers={"Content-Type": "application/json"},
        response_check=lambda r: r.status_code == 200,
        log_response=True,      # 응답 본문 로그 출력
        do_xcom_push=True,      # 응답 본문 XCom 저장 → analyze_result 에서 참조
    )

    # Task 3: 결과 분석 및 이상 탐지
    analyze_result = PythonOperator(
        task_id="analyze_result",
        python_callable=analyze_timeout_result,
    )

    # 의존성: 헬스 체크 통과 → 처리 실행 → 결과 분석
    health_check >> process_timeouts >> analyze_result
