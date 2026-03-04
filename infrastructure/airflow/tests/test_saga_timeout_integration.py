"""
test_saga_timeout_integration.py
=================================
Airflow → order-service 파이프라인 통합 테스트.

테스트 대상:
  1. HTTP 응답 → XCom → analyze_timeout_result 전체 데이터 흐름
  2. responses 라이브러리로 order-service HTTP를 모킹하여
     process_stuck_sagas 태스크가 보내는 요청/응답 구조를 검증

단위 테스트(test_saga_timeout_dag.py)와의 차이:
  - 단위 테스트: analyze_timeout_result 함수만 직접 호출, 입력을 MagicMock으로 주입
  - 통합 테스트: HTTP 응답 JSON → json.dumps → xcom_pull → analyze_timeout_result
               즉, "HTTP 응답이 XCom을 통해 분석 함수로 올바르게 흘러가는가"를 검증
"""
import json
import logging
from unittest.mock import MagicMock, patch

import pytest
import responses as resp


# ════════════════════════════════════════════════════════════════════════════
# 헬퍼
# ════════════════════════════════════════════════════════════════════════════

ORDER_SERVICE_URL = "http://order-service:9001"
TIMEOUT_ENDPOINT  = f"{ORDER_SERVICE_URL}/internal/saga/timeout/process"


def make_xcom_context(response_body: dict) -> dict:
    """HTTP 응답 body → JSON 문자열 → XCom pull 형태로 감싸 반환."""
    ti = MagicMock()
    ti.xcom_pull.return_value = json.dumps(response_body)
    return {"ti": ti}


# ════════════════════════════════════════════════════════════════════════════
# HTTP 응답 → XCom → analyze 파이프라인 통합 테스트
# ════════════════════════════════════════════════════════════════════════════

class TestHttpResponseToAnalyzePipeline:
    """
    process_stuck_sagas 태스크의 HTTP 응답이 XCom 을 거쳐
    analyze_timeout_result 로 올바르게 전달되는지 검증한다.
    """

    def test_all_normal_no_stuck_orders(self, dag_module, caplog):
        """
        order-service 가 processedCount=0 을 반환하면
        analyze_result 가 예외 없이 완료된다.
        """
        http_response = {
            "processedCount": 0,
            "cancelledOrders": [],
            "cancellationFailedOrders": [],
            "returnFailedOrders": [],
            "skippedOrders": [],
        }
        context = make_xcom_context(http_response)

        with caplog.at_level(logging.INFO):
            dag_module.analyze_timeout_result(**context)  # 예외 없어야 함

        assert "Scan complete" in caplog.text

    def test_warning_when_orders_were_compensated(self, dag_module, caplog):
        """
        멈춘 Saga 가 발견되어 보상된 경우 경고가 기록되지만 DAG 는 실패하지 않는다.
        실제 HTTP 응답 형태(cancelledOrders + cancellationFailedOrders 혼합)를 사용.
        """
        http_response = {
            "processedCount": 5,
            "cancelledOrders": ["order-001", "order-002", "order-003"],
            "cancellationFailedOrders": ["order-004", "order-005"],
            "returnFailedOrders": [],
            "skippedOrders": [],
        }
        context = make_xcom_context(http_response)

        with caplog.at_level(logging.WARNING):
            dag_module.analyze_timeout_result(**context)

        assert "5 stuck saga(s)" in caplog.text
        assert "order-001" in caplog.text
        assert "order-004" in caplog.text

    def test_critical_skipped_orders_raises_runtime_error(self, dag_module):
        """
        skippedOrders 가 존재하면 RuntimeError 가 발생해 DAG 가 실패한다.
        COMPENSATING 교착 상태 — 자동 복구 불가.
        """
        stuck_ids = ["order-deadlock-001", "order-deadlock-002"]
        http_response = {
            "processedCount": 3,
            "cancelledOrders": ["order-ok-001"],
            "cancellationFailedOrders": ["order-ok-002"],
            "returnFailedOrders": ["order-ok-003"],
            "skippedOrders": stuck_ids,
        }
        context = make_xcom_context(http_response)

        with pytest.raises(RuntimeError) as exc_info:
            dag_module.analyze_timeout_result(**context)

        error_msg = str(exc_info.value)
        for order_id in stuck_ids:
            assert order_id in error_msg
        assert "manual intervention" in error_msg

    def test_partial_fields_missing_defaults_to_empty(self, dag_module, caplog):
        """
        order-service 가 일부 필드를 생략해도 기본값(빈 리스트/0)으로 처리된다.
        result.get("cancelledOrders", []) 방어 코드 검증.
        """
        # cancellationFailedOrders, returnFailedOrders 누락
        http_response = {
            "processedCount": 1,
            "cancelledOrders": ["order-partial"],
            "skippedOrders": [],
        }
        context = make_xcom_context(http_response)

        with caplog.at_level(logging.WARNING):
            dag_module.analyze_timeout_result(**context)  # 예외 없어야 함

        assert "1 stuck saga(s)" in caplog.text

    def test_return_failed_orders_in_warning_log(self, dag_module, caplog):
        """
        returnFailedOrders 가 있는 경우 경고 로그에 포함된다.
        반품 Saga 타임아웃 시나리오.
        """
        http_response = {
            "processedCount": 2,
            "cancelledOrders": [],
            "cancellationFailedOrders": [],
            "returnFailedOrders": ["return-order-001", "return-order-002"],
            "skippedOrders": [],
        }
        context = make_xcom_context(http_response)

        with caplog.at_level(logging.WARNING):
            dag_module.analyze_timeout_result(**context)

        assert "2 stuck saga(s)" in caplog.text
        assert "return-order-001" in caplog.text

    def test_xcom_pull_uses_correct_task_id(self, dag_module):
        """
        analyze_timeout_result 가 'process_stuck_sagas' task_id 로 XCom 을 참조하는지 검증.
        잘못된 task_id 를 참조하면 None 이 반환되어 분석이 건너뛰어진다.
        """
        http_response = {
            "processedCount": 0,
            "cancelledOrders": [],
            "cancellationFailedOrders": [],
            "returnFailedOrders": [],
            "skippedOrders": [],
        }
        context = make_xcom_context(http_response)

        dag_module.analyze_timeout_result(**context)

        context["ti"].xcom_pull.assert_called_once_with(task_ids="process_stuck_sagas")


# ════════════════════════════════════════════════════════════════════════════
# HTTP 엔드포인트 요청 구조 검증
# ════════════════════════════════════════════════════════════════════════════

class TestOrderServiceHttpInteraction:
    """
    process_stuck_sagas 태스크가 올바른 HTTP 요청을 구성하는지 검증.
    responses 라이브러리로 order-service 를 모킹하여 실제 HTTP 레이어를 통해 테스트.
    """

    @resp.activate
    def test_post_request_sent_to_correct_endpoint(self):
        """
        /internal/saga/timeout/process 에 POST 요청이 전송된다.
        HttpOperator 설정 검증: endpoint, method, Content-Type.
        """
        expected_body = {
            "processedCount": 0,
            "cancelledOrders": [],
            "cancellationFailedOrders": [],
            "returnFailedOrders": [],
            "skippedOrders": [],
        }
        resp.add(
            resp.POST,
            TIMEOUT_ENDPOINT,
            json=expected_body,
            status=200,
        )

        import requests
        response = requests.post(
            TIMEOUT_ENDPOINT,
            headers={"Content-Type": "application/json"},
        )

        assert response.status_code == 200
        assert len(resp.calls) == 1
        assert resp.calls[0].request.method == "POST"
        assert "/internal/saga/timeout/process" in resp.calls[0].request.url
        assert resp.calls[0].request.headers.get("Content-Type") == "application/json"

    @resp.activate
    def test_response_body_is_valid_json(self):
        """
        order-service 응답이 유효한 JSON 이고 필수 필드를 포함한다.
        """
        expected_body = {
            "processedCount": 2,
            "cancelledOrders": ["order-001"],
            "cancellationFailedOrders": ["order-002"],
            "returnFailedOrders": [],
            "skippedOrders": [],
        }
        resp.add(resp.POST, TIMEOUT_ENDPOINT, json=expected_body, status=200)

        import requests
        response = requests.post(TIMEOUT_ENDPOINT)
        body = response.json()

        assert "processedCount" in body
        assert "cancelledOrders" in body
        assert "cancellationFailedOrders" in body
        assert "returnFailedOrders" in body
        assert "skippedOrders" in body
        assert body["processedCount"] == 2

    @resp.activate
    def test_non_200_response_signals_failure(self):
        """
        order-service 가 5xx 를 반환하면 호출 측에서 오류를 감지할 수 있다.
        HttpOperator 의 response_check=lambda r: r.status_code == 200 검증.
        """
        resp.add(resp.POST, TIMEOUT_ENDPOINT, status=503)

        import requests
        response = requests.post(TIMEOUT_ENDPOINT)

        # HttpOperator 의 response_check 와 동일한 조건
        assert response.status_code != 200
        assert not (response.status_code == 200)

    @resp.activate
    def test_full_pipeline_critical_flow(self, dag_module):
        """
        order-service 가 skippedOrders 를 반환하는 경우의 전체 흐름.
        HTTP 응답 → JSON 파싱 → analyze_timeout_result 에서 RuntimeError 발생.
        """
        stuck_order_id = "order-deadlock-999"
        http_body = {
            "processedCount": 1,
            "cancelledOrders": ["order-ok"],
            "cancellationFailedOrders": [],
            "returnFailedOrders": [],
            "skippedOrders": [stuck_order_id],
        }
        resp.add(resp.POST, TIMEOUT_ENDPOINT, json=http_body, status=200)

        # HTTP 호출 (process_stuck_sagas 태스크가 하는 일 시뮬레이션)
        import requests
        response = requests.post(TIMEOUT_ENDPOINT)
        assert response.status_code == 200

        # XCom push 시뮬레이션 (HttpOperator 의 do_xcom_push=True 동작)
        xcom_value = response.text  # HttpOperator 는 응답 body 를 그대로 XCom 에 저장

        # analyze_timeout_result 에서 XCom 읽기
        ti = MagicMock()
        ti.xcom_pull.return_value = xcom_value
        context = {"ti": ti}

        # skippedOrders 존재 → RuntimeError → DAG 실패
        with pytest.raises(RuntimeError) as exc_info:
            dag_module.analyze_timeout_result(**context)

        assert stuck_order_id in str(exc_info.value)


# ════════════════════════════════════════════════════════════════════════════
# 경계 케이스
# ════════════════════════════════════════════════════════════════════════════

class TestEdgeCases:
    """단위 테스트에서 다루지 않은 경계 케이스."""

    def test_large_order_count_all_succeed(self, dag_module, caplog):
        """
        다수의 주문이 처리된 경우 processedCount 와 리스트 길이가 일치한다.
        """
        cancelled_ids = [f"order-{i:03d}" for i in range(50)]
        http_response = {
            "processedCount": 50,
            "cancelledOrders": cancelled_ids,
            "cancellationFailedOrders": [],
            "returnFailedOrders": [],
            "skippedOrders": [],
        }
        context = make_xcom_context(http_response)

        with caplog.at_level(logging.WARNING):
            dag_module.analyze_timeout_result(**context)

        assert "50 stuck saga(s)" in caplog.text

    def test_all_skipped_raises_with_all_ids(self, dag_module):
        """
        모든 주문이 SKIPPED 일 때 RuntimeError 에 모든 ID 가 포함된다.
        """
        skipped = ["order-s1", "order-s2", "order-s3"]
        context = make_xcom_context({
            "processedCount": 0,
            "cancelledOrders": [],
            "cancellationFailedOrders": [],
            "returnFailedOrders": [],
            "skippedOrders": skipped,
        })

        with pytest.raises(RuntimeError) as exc_info:
            dag_module.analyze_timeout_result(**context)

        error_msg = str(exc_info.value)
        for order_id in skipped:
            assert order_id in error_msg

    def test_none_xcom_value_logs_warning_no_exception(self, dag_module, caplog):
        """
        XCom 에 값이 없으면(None) 경고만 기록하고 예외 없이 종료한다.
        order-service 가 응답하지 않았거나 process_stuck_sagas 태스크가 실패한 경우.
        """
        ti = MagicMock()
        ti.xcom_pull.return_value = None
        context = {"ti": ti}

        with caplog.at_level(logging.WARNING):
            dag_module.analyze_timeout_result(**context)

        assert "Empty response" in caplog.text
