"""
test_saga_timeout_dag.py
========================
saga_timeout_processor DAG 단위 테스트.

테스트 대상:
  1. DAG 구조 — 태스크 ID, 의존성, 설정값
  2. analyze_timeout_result — 응답 파싱 및 이상 탐지 로직
"""
import json
import logging
from datetime import timedelta
from unittest.mock import MagicMock, patch

import pytest


# ════════════════════════════════════════════════════════════════════════════
# DAG 구조 테스트
# ════════════════════════════════════════════════════════════════════════════

class TestDagStructure:
    """DAG 자체의 구성(태스크, 의존성, 설정)을 검증한다."""

    def test_dag_loads_without_import_errors(self, dag_module):
        """DAG 모듈이 오류 없이 임포트된다."""
        assert dag_module is not None
        assert hasattr(dag_module, "dag")

    def test_dag_id(self, dag_module):
        assert dag_module.dag.dag_id == "saga_timeout_processor"

    def test_dag_has_expected_tasks(self, dag_module):
        """3개의 태스크가 정확히 존재한다."""
        task_ids = {t.task_id for t in dag_module.dag.tasks}
        assert task_ids == {
            "order_service_health_check",
            "process_stuck_sagas",
            "analyze_result",
        }

    def test_task_dependency_health_check_to_process(self, dag_module):
        """process_stuck_sagas 는 order_service_health_check 에 의존한다."""
        process_task = dag_module.dag.get_task("process_stuck_sagas")
        upstream_ids = {t.task_id for t in process_task.upstream_list}
        assert "order_service_health_check" in upstream_ids

    def test_task_dependency_process_to_analyze(self, dag_module):
        """analyze_result 는 process_stuck_sagas 에 의존한다."""
        analyze_task = dag_module.dag.get_task("analyze_result")
        upstream_ids = {t.task_id for t in analyze_task.upstream_list}
        assert "process_stuck_sagas" in upstream_ids

    def test_health_check_has_no_upstream(self, dag_module):
        """order_service_health_check 는 최상위 태스크다."""
        health_task = dag_module.dag.get_task("order_service_health_check")
        assert health_task.upstream_list == []

    def test_schedule_is_30_minutes(self, dag_module):
        """30분 주기로 실행된다 (생성 Saga 타임아웃 임계값과 동일)."""
        assert dag_module.dag.schedule == timedelta(minutes=30)

    def test_max_active_runs_is_one(self, dag_module):
        """동시 실행이 방지된다 (멱등하지만 중복 실행 불필요)."""
        assert dag_module.dag.max_active_runs == 1

    def test_catchup_disabled(self, dag_module):
        """과거 기간 백필 실행이 비활성화된다."""
        assert dag_module.dag.catchup is False

    def test_default_args_retries(self, dag_module):
        """재시도 횟수가 3회로 설정된다."""
        assert dag_module.dag.default_args["retries"] == 3

    def test_default_args_exponential_backoff(self, dag_module):
        """재시도 간격이 지수 백오프로 설정된다."""
        assert dag_module.dag.default_args["retry_exponential_backoff"] is True


# ════════════════════════════════════════════════════════════════════════════
# analyze_timeout_result 단위 테스트
# ════════════════════════════════════════════════════════════════════════════

class TestAnalyzeTimeoutResult:
    """
    analyze_timeout_result Python callable 의 각 시나리오를 검증한다.

    정상 : processedCount == 0, skippedOrders == [] → 예외 없음
    경고 : processedCount > 0,  skippedOrders == [] → 경고 로그, 예외 없음
    심각 : skippedOrders 비어있지 않음              → RuntimeError (DAG 실패 트리거)
    """

    def test_normal_no_stuck_orders(self, dag_module, make_context, caplog):
        """멈춘 Saga 가 없는 경우 예외 없이 정상 종료된다."""
        context = make_context({
            "processedCount": 0,
            "cancelledOrders": [],
            "cancellationFailedOrders": [],
            "returnFailedOrders": [],
            "skippedOrders": [],
        })

        with caplog.at_level(logging.INFO):
            dag_module.analyze_timeout_result(**context)  # 예외 없어야 함

        assert "Scan complete" in caplog.text

    def test_warning_when_orders_were_processed(self, dag_module, make_context, caplog):
        """
        멈춘 Saga 가 발견되어 보상 처리된 경우 경고 로그가 기록되지만
        DAG 는 실패하지 않는다 (근본 원인 조사 필요 신호).
        """
        context = make_context({
            "processedCount": 2,
            "cancelledOrders": ["order-001"],
            "cancellationFailedOrders": ["order-002"],
            "returnFailedOrders": [],
            "skippedOrders": [],
        })

        with caplog.at_level(logging.WARNING):
            dag_module.analyze_timeout_result(**context)  # 예외 없어야 함

        assert "2 stuck saga(s)" in caplog.text
        assert "order-001" in caplog.text
        assert "order-002" in caplog.text

    def test_critical_compensating_deadlock_raises(self, dag_module, make_context):
        """
        COMPENSATING 교착 상태(skippedOrders 비어있지 않음)는 RuntimeError 를 발생시켜
        DAG 를 실패 처리하고 이메일 알림을 트리거한다.
        """
        context = make_context({
            "processedCount": 1,
            "cancelledOrders": ["order-001"],
            "cancellationFailedOrders": [],
            "returnFailedOrders": [],
            "skippedOrders": ["order-999"],
        })

        with pytest.raises(RuntimeError) as exc_info:
            dag_module.analyze_timeout_result(**context)

        error_msg = str(exc_info.value)
        assert "order-999" in error_msg
        assert "manual intervention" in error_msg

    def test_critical_message_contains_all_stuck_order_ids(self, dag_module, make_context):
        """RuntimeError 메시지에 교착 상태 주문 ID 가 모두 포함된다."""
        stuck_orders = ["order-aaa", "order-bbb", "order-ccc"]
        context = make_context({
            "processedCount": 0,
            "cancelledOrders": [],
            "cancellationFailedOrders": [],
            "returnFailedOrders": [],
            "skippedOrders": stuck_orders,
        })

        with pytest.raises(RuntimeError) as exc_info:
            dag_module.analyze_timeout_result(**context)

        for order_id in stuck_orders:
            assert order_id in str(exc_info.value)

    def test_empty_response_does_not_raise(self, dag_module, caplog):
        """
        order-service 가 빈 응답을 반환하는 경우 (네트워크 이상 등)
        예외 없이 경고 로그만 기록하고 종료한다.
        """
        ti = MagicMock()
        ti.xcom_pull.return_value = None  # XCom 에 응답 없음
        context = {"ti": ti}

        with caplog.at_level(logging.WARNING):
            dag_module.analyze_timeout_result(**context)

        assert "Empty response" in caplog.text

    def test_processed_and_skipped_both_present_raises(self, dag_module, make_context):
        """
        processedCount > 0 이면서 skippedOrders 도 비어있지 않은 경우
        RuntimeError 가 발생한다 (skippedOrders 가 우선).
        """
        context = make_context({
            "processedCount": 3,
            "cancelledOrders": ["order-001", "order-002"],
            "cancellationFailedOrders": ["order-003"],
            "returnFailedOrders": [],
            "skippedOrders": ["order-deadlock"],
        })

        with pytest.raises(RuntimeError):
            dag_module.analyze_timeout_result(**context)

    def test_xcom_pull_called_with_correct_task_id(self, dag_module, make_context):
        """
        analyze_result 가 process_stuck_sagas 의 XCom 을 참조하는지 확인한다.
        잘못된 task_ids 를 참조하면 None 이 반환되어 분석이 건너뛰어진다.
        """
        context = make_context({
            "processedCount": 0,
            "cancelledOrders": [],
            "cancellationFailedOrders": [],
            "returnFailedOrders": [],
            "skippedOrders": [],
        })

        dag_module.analyze_timeout_result(**context)

        context["ti"].xcom_pull.assert_called_once_with(task_ids="process_stuck_sagas")


# ════════════════════════════════════════════════════════════════════════════
# on_dag_failure 콜백 테스트
# ════════════════════════════════════════════════════════════════════════════

class TestOnDagFailure:
    """DAG 실패 콜백이 필요한 정보를 올바르게 로깅하는지 검증한다."""

    def test_failure_callback_logs_error(self, dag_module, caplog):
        """실패 시 dag_id, task_id, exception 이 로그에 기록된다."""
        mock_dag_run = MagicMock()
        mock_dag_run.dag_id = "saga_timeout_processor"

        mock_ti = MagicMock()
        mock_ti.task_id = "process_stuck_sagas"

        context = {
            "dag_run": mock_dag_run,
            "task_instance": mock_ti,
            "exception": RuntimeError("connection refused"),
            "run_id": "scheduled__2025-01-01T00:00:00",
        }

        with caplog.at_level(logging.ERROR):
            dag_module.on_dag_failure(context)

        assert "saga_timeout_processor" in caplog.text
        assert "process_stuck_sagas" in caplog.text
        assert "connection refused" in caplog.text
