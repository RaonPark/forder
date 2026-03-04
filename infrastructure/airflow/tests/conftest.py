"""
conftest.py
===========
Airflow 테스트 환경 설정.

Variable.get 은 DAG 모듈 임포트 시점에 호출되므로,
sys.path 설정과 환경 변수 구성을 import 전에 완료해야 한다.
"""
import os
import sys
from pathlib import Path
from unittest.mock import patch

import pytest

# ── 1. Airflow 환경 변수 (어떤 airflow import 보다 먼저 설정) ─────────────────
os.environ.setdefault("AIRFLOW__CORE__UNIT_TEST_MODE", "True")
os.environ.setdefault("AIRFLOW_HOME", "/tmp/airflow_test")
os.environ.setdefault(
    "AIRFLOW__DATABASE__SQL_ALCHEMY_CONN",
    "sqlite:////tmp/airflow_test/airflow.db",
)

# ── 2. dags/ 가 포함된 상위 디렉토리를 sys.path 에 추가 ──────────────────────
# infrastructure/airflow/ 를 추가 → dags.saga_timeout_dag 로 임포트 가능
sys.path.insert(0, str(Path(__file__).parent.parent))


# ── 3. DAG 모듈 픽스처 ───────────────────────────────────────────────────────

@pytest.fixture(scope="session")
def dag_module():
    """
    Variable.get 을 패치한 상태에서 DAG 모듈을 임포트한다.

    scope="session": 테스트 세션 전체에서 한 번만 임포트
    (Variable.get 패치는 임포트 시점에만 필요하므로 이후에는 해제해도 무방)
    """
    module_name = "dags.saga_timeout_dag"
    # 혹시 이전 테스트에서 캐시된 모듈이 있으면 제거
    sys.modules.pop(module_name, None)

    with patch("airflow.models.Variable.get", return_value="test@company.com"):
        import importlib
        module = importlib.import_module(module_name)

    return module


@pytest.fixture
def make_context():
    """
    analyze_timeout_result 에 전달할 Airflow context 팩토리.
    ti.xcom_pull(task_ids=...) 의 반환값을 JSON 문자열로 설정한다.
    """
    from unittest.mock import MagicMock

    def _make(response_body: dict) -> dict:
        ti = MagicMock()
        ti.xcom_pull.return_value = __import__("json").dumps(response_body)
        return {"ti": ti}

    return _make
