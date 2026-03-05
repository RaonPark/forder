# Forder 모니터링 가이드 — Prometheus & Grafana

## 목차
1. [모니터링이 필요한 이유](#1-모니터링이-필요한-이유)
2. [구성 요소와 역할](#2-구성-요소와-역할)
3. [Prometheus 이론](#3-prometheus-이론)
4. [Grafana 이론](#4-grafana-이론)
5. [Micrometer — Spring과 Prometheus의 연결 고리](#5-micrometer--spring과-prometheus의-연결-고리)
6. [Forder에서의 구성](#6-forder에서의-구성)
7. [실행 및 접속](#7-실행-및-접속)
8. [Grafana 대시보드 설정](#8-grafana-대시보드-설정)
9. [주요 메트릭 레퍼런스](#9-주요-메트릭-레퍼런스)

---

## 1. 모니터링이 필요한 이유

마이크로서비스 아키텍처는 서비스 수가 많아질수록 장애 원인 파악이 어렵다.

```
사용자: "주문이 안 돼요"
          ↓
gateway → order-service → inventory-service → payment-service → delivery-service
                 ↑
         어디서 느려진 걸까?
```

모니터링 없이는 각 서비스에 SSH 접속해서 로그를 뒤져야 한다.
모니터링이 있으면 **대시보드 한 화면**에서 병목 지점을 즉시 특정할 수 있다.

---

## 2. 구성 요소와 역할

```
┌─────────────────────────────────────────────────────┐
│                   Spring Boot 서비스                   │
│                                                     │
│  비즈니스 로직 → Micrometer → /actuator/prometheus    │
│                   (메트릭 기록)    (메트릭 노출)         │
└──────────────────────┬──────────────────────────────┘
                       │ HTTP GET /actuator/prometheus
                       │ (15초마다 pull)
              ┌────────▼────────┐
              │   Prometheus    │  ← 메트릭 수집·저장 (시계열 DB)
              │   :9090         │
              └────────┬────────┘
                       │ 데이터소스 연결
              ┌────────▼────────┐
              │    Grafana      │  ← 시각화 대시보드
              │    :3000        │
              └─────────────────┘
```

| 구성 요소 | 역할 |
|-----------|------|
| **Micrometer** | Spring 내부에서 메트릭을 기록하는 라이브러리. Prometheus 포맷으로 변환 |
| **Spring Actuator** | `/actuator/prometheus` 엔드포인트를 HTTP로 노출 |
| **Prometheus** | 각 서비스를 주기적으로 긁어(pull) 시계열 데이터베이스에 저장 |
| **Grafana** | Prometheus에 PromQL 쿼리를 날려 결과를 그래프로 시각화 |

---

## 3. Prometheus 이론

### 3.1 Pull 방식 vs Push 방식

대부분의 모니터링 도구는 앱이 데이터를 서버에 **보내는(push)** 방식이다.
Prometheus는 반대로 서버가 앱을 **긁어오는(pull)** 방식이다.

```
[Push 방식]  앱 → → → 모니터링 서버    (앱이 주도)
[Pull 방식]  앱 ← ← ← Prometheus      (Prometheus가 주도)
```

**Pull 방식의 장점**:
- 앱이 죽으면 Prometheus가 scrape 실패를 감지 → 알림 발송 가능
- 앱 코드에 모니터링 서버 주소를 하드코딩할 필요 없음
- Prometheus 서버 하나가 여러 타겟을 유연하게 관리

### 3.2 메트릭 타입 4가지

#### Counter — 단조 증가하는 누적 값

```
http_server_requests_seconds_count{uri="/v1/orders"} 1547
```
- 재시작 시 0으로 초기화됨
- 값이 줄어드는 일이 없음
- 주로 `rate()` 함수로 초당 증가율을 계산해 사용

```promql
# 최근 1분간 /v1/orders 초당 요청 수
rate(http_server_requests_seconds_count{uri="/v1/orders"}[1m])
```

#### Gauge — 현재 상태를 나타내는 순간값

```
jvm_memory_used_bytes{area="heap"} 134217728
mongodb_driver_pool_size 10
```
- 올라갔다 내려갔다 자유롭게 변함
- 현재 스냅샷 그대로 의미 있음

#### Histogram — 분포 측정 (요청 응답시간 등)

```
http_server_requests_seconds_bucket{le="0.05"} 1200   # 50ms 이하 요청 수
http_server_requests_seconds_bucket{le="0.1"}  1500
http_server_requests_seconds_bucket{le="0.5"}  1547
http_server_requests_seconds_sum              48.3    # 전체 응답시간 합계
http_server_requests_seconds_count            1547    # 전체 요청 수
```

```promql
# P99 응답시간 (상위 1% 느린 요청의 임계값)
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))
```

#### Summary — 클라이언트 사이드 분위수 (Histogram과 유사, 덜 쓰임)

### 3.3 레이블 (Labels)

메트릭에 차원을 추가하는 키-값 쌍이다.

```
http_server_requests_seconds_count{
  application="order-service",   ← 어떤 서비스?
  method="POST",                 ← HTTP 메서드?
  uri="/v1/orders",              ← 어떤 경로?
  status="200"                   ← 응답 코드?
} 423
```

레이블 덕분에 하나의 메트릭으로 다양한 질문에 답할 수 있다:
- `POST /v1/orders`의 성공률은?
- `order-service`의 5xx 비율은?
- 가장 느린 API 경로는?

### 3.4 PromQL 핵심 함수

```promql
# 초당 요청 수 (지난 5분 평균)
rate(http_server_requests_seconds_count[5m])

# 에러율 (5xx 비율)
rate(http_server_requests_seconds_count{status=~"5.."}[5m])
  /
rate(http_server_requests_seconds_count[5m])

# P99 응답시간
histogram_quantile(0.99,
  rate(http_server_requests_seconds_bucket[5m])
)

# 힙 메모리 사용률
jvm_memory_used_bytes{area="heap"}
  /
jvm_memory_max_bytes{area="heap"}
```

---

## 4. Grafana 이론

### 4.1 역할

Grafana 자체는 데이터를 저장하지 않는다. **쿼리 엔진 + 시각화 도구**다.

```
Grafana → (PromQL 쿼리) → Prometheus → 결과 반환 → 그래프 렌더링
```

### 4.2 핵심 개념

| 개념 | 설명 |
|------|------|
| **Data Source** | Prometheus, Elasticsearch 등 데이터 출처 연결 설정 |
| **Dashboard** | 여러 Panel을 모아 놓은 화면 |
| **Panel** | 단일 차트 또는 수치 표시 단위 |
| **Variable** | 대시보드 상단에서 필터링하는 드롭다운 (서비스명, 환경 등) |
| **Provisioning** | 파일로 Data Source·Dashboard를 자동 설정 |

### 4.3 Provisioning (자동 설정)

이 프로젝트는 컨테이너 실행 시 Prometheus 데이터소스가 자동으로 연결된다.

```
infrastructure/docker/grafana/provisioning/
└── datasources/
    └── prometheus.yml  ← Grafana가 시작할 때 자동으로 읽어 설정
```

---

## 5. Micrometer — Spring과 Prometheus의 연결 고리

### 5.1 역할

Micrometer는 Spring Boot 내부에 탑재된 **메트릭 수집 추상화 레이어**다.

```
Spring Boot 내부           Micrometer           Prometheus
─────────────          ──────────────        ──────────────
HTTP 요청 처리  →  요청 수/응답시간 기록  →  Prometheus 포맷 변환
MongoDB 쿼리    →  쿼리 시간 기록        →  /actuator/prometheus
JVM 상태        →  힙/GC/스레드 기록     →  텍스트 노출
```

### 5.2 필요한 의존성

```kotlin
// build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-actuator")  // 엔드포인트 노출
runtimeOnly("io.micrometer:micrometer-registry-prometheus")              // Prometheus 포맷 변환
```

- `starter-actuator`: `/actuator/prometheus` HTTP 엔드포인트를 만들어줌
- `micrometer-registry-prometheus`: 내부 메트릭을 Prometheus 텍스트 포맷으로 직렬화

### 5.3 application.yml 설정

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus  # 외부에 노출할 엔드포인트
  metrics:
    tags:
      application: ${spring.application.name}  # 모든 메트릭에 service 레이블 자동 추가
```

`application` 태그를 추가하면 Grafana에서 서비스별 필터링이 가능해진다:
```promql
rate(http_server_requests_seconds_count{application="order-service"}[5m])
```

---

## 6. Forder에서의 구성

### 6.1 전체 파일 구조

```
infrastructure/docker/
├── docker-compose.yml              ← prometheus, grafana 서비스 정의
├── prometheus/
│   └── prometheus.yml             ← scrape 대상 서비스 목록
└── grafana/
    └── provisioning/
        └── datasources/
            └── prometheus.yml     ← Prometheus 데이터소스 자동 연결
```

### 6.2 서비스별 포트 및 Scrape 대상

| 서비스 | 내부 포트 (server.port) | Prometheus scrape target |
|--------|------------------------|--------------------------|
| gateway | 8080 | `gateway:8080` |
| order-service | 9001 | `order-service:9001` |
| payment-service | 9002 | `payment-service:9002` |
| delivery-service | 9003 | `delivery-service:9003` |
| inventory-service | 9004 | `inventory-service:9004` |
| product-service | 9005 | `product-service:9005` |
| query-service | 9006 | `query-service:9006` |
| kafka-streams-app | 9007 | `kafka-streams-app:9007` |

> **포트 원칙**: `server.port` (내부 포트) = Docker host 매핑 포트 = Prometheus scrape 포트
> 세 값이 일치해야 혼란이 없다.

### 6.3 로컬(IDE) 개발 시 주의사항

Prometheus는 Docker 컨테이너 안에서 실행된다.
서비스를 IDE에서 실행하면 `order-service:9001`이 아닌
**`host.docker.internal:9001`** (Mac/Windows) 또는 **`172.17.0.1:9001`** (Linux)로 접근해야 한다.

개발 중 Prometheus scrape 실패는 서비스 동작에 영향을 주지 않으니 무시해도 된다.

---

## 7. 실행 및 접속

### 7.1 인프라 실행

```bash
cd infrastructure/docker
docker-compose up -d
```

### 7.2 접속 URL

| 서비스 | URL | 비고 |
|--------|-----|------|
| Prometheus | http://localhost:9090 | 메트릭 직접 조회 |
| Grafana | http://localhost:3000 | admin / admin |

### 7.3 Prometheus UI에서 메트릭 확인

1. http://localhost:9090 접속
2. 상단 검색창에 메트릭 입력:
   ```
   http_server_requests_seconds_count
   ```
3. **Graph** 탭에서 시계열 확인
4. **Status → Targets** 에서 각 서비스 scrape 상태 확인

---

## 8. Grafana 대시보드 설정

### 8.1 초기 접속

1. http://localhost:3000 접속
2. ID: `admin` / PW: `admin` 로그인
3. 비밀번호 변경 요구 시 적당히 설정 (개발 환경이면 건너뛰기 가능)

### 8.2 권장 대시보드 Import

**Dashboards → New → Import → ID 입력 → Load**

| Dashboard ID | 이름 | 용도 |
|-------------|------|------|
| `4701` | JVM Micrometer | 힙·GC·스레드·클래스로더 |
| `11378` | Spring Boot 2.1 Statistics | HTTP 요청·응답시간·에러율 |
| `7587` | Spring Boot Actuator | 엔드포인트별 상세 통계 |
| `1860` | Node Exporter Full | 서버 CPU·메모리 (Node Exporter 별도 설치 필요) |

Import 후 **Data Source**를 `Prometheus`로 선택하면 즉시 사용 가능하다.

### 8.3 서비스별 필터링

모든 서비스의 메트릭에 `application` 레이블이 붙어 있으므로
대시보드 상단 Variable에서 서비스를 선택해 필터링할 수 있다.

---

## 9. 주요 메트릭 레퍼런스

### HTTP 요청

```promql
# 서비스별 초당 요청 수
rate(http_server_requests_seconds_count{application="order-service"}[5m])

# 에러율 (5xx 비율)
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (application)
  /
sum(rate(http_server_requests_seconds_count[5m])) by (application)

# P99 응답시간 (서비스별)
histogram_quantile(0.99,
  sum(rate(http_server_requests_seconds_bucket[5m])) by (application, le)
)
```

### JVM

```promql
# 힙 사용률
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} * 100

# GC 빈도 (초당)
rate(jvm_gc_pause_seconds_count[5m])

# 활성 스레드 수
jvm_threads_live_threads
```

### MongoDB (Spring Data)

```promql
# MongoDB 커넥션 풀 체크아웃 대기시간
mongodb_driver_pool_waitqueuesize

# 전체 커넥션 수
mongodb_driver_pool_size
```

### Kafka

```promql
# Kafka Consumer Lag (컨슈머가 얼마나 뒤처졌는지)
kafka_consumer_fetch_manager_records_lag{application="order-service"}

# Producer 성공/실패 레코드 수
rate(kafka_producer_record_send_total[5m])
```

### Forder 핵심 SLI 예시

```promql
# 주문 생성 API 가용성 (성공률)
sum(rate(http_server_requests_seconds_count{
  application="order-service",
  uri="/v1/orders",
  method="POST",
  status!~"5.."
}[5m]))
/
sum(rate(http_server_requests_seconds_count{
  application="order-service",
  uri="/v1/orders",
  method="POST"
}[5m]))

# 결제 처리 P95 응답시간
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{
    application="payment-service"
  }[5m])
)
```
