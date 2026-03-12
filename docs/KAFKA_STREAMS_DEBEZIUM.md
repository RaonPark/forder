# Debezium CDC + Kafka Streams + GlobalKTable 이론 및 설계

## 목차

1. [CDC(Change Data Capture)란](#1-cdcchange-data-capture란)
2. [Debezium의 동작 원리](#2-debezium의-동작-원리)
3. [ExtractNewDocumentState SMT](#3-extractnewdocumentstate-smt)
4. [Kafka Connect Converter](#4-kafka-connect-converter)
5. [Kafka Streams 핵심 개념](#5-kafka-streams-핵심-개념)
6. [GlobalKTable — 왜 KTable이 아닌가](#6-globalkTable--왜-ktable이-아닌가)
7. [이 프로젝트의 enrichment 아키텍처](#7-이-프로젝트의-enrichment-아키텍처)
8. [설계 결정 근거](#8-설계-결정-근거)
9. [트러블슈팅 레퍼런스](#9-트러블슈팅-레퍼런스)

---

## 1. CDC(Change Data Capture)란

CDC는 DB의 변경(INSERT / UPDATE / DELETE)을 감지해서 다른 시스템에 전달하는 패턴이다.

### 왜 필요한가

서비스 간 데이터를 동기화하는 가장 단순한 방법은 **이중 쓰기(dual write)** 다.

```
// 안티패턴: dual write
orderRepository.save(order)      // MongoDB에 저장
kafkaProducer.send("orders", ...) // Kafka에 발행
```

이 코드는 두 번째 줄에서 크래시가 나면 DB는 저장됐지만 Kafka에는 이벤트가 없는 상태가 된다.
트랜잭션으로 묶을 수도 없다 — MongoDB와 Kafka는 서로 다른 트랜잭션 컨텍스트다.

CDC는 이 문제를 근본적으로 해결한다. **DB에 한 번만 쓰면, 변경이 자동으로 Kafka로 흘러간다.**

```
orderRepository.save(order)  // DB에만 쓴다
                              // Debezium이 change stream을 보다가 자동으로 Kafka에 발행
```

### MongoDB에서 CDC가 동작하는 방식

MongoDB는 내부적으로 **oplog(operation log)** 를 유지한다.
Replica Set의 모든 쓰기 연산이 oplog에 기록되며, Debezium은 이 oplog를 구독한다.

```
MongoDB Primary
  ├── 실제 데이터 (orders, payments, ...)
  └── oplog (변경 이력)
       └── Debezium이 change stream API로 구독
```

Replica Set이 필수인 이유: change stream은 oplog 기반이고, oplog는 Replica Set에서만 활성화된다.
단독(Standalone) MongoDB 인스턴스는 change stream을 지원하지 않는다.

---

## 2. Debezium의 동작 원리

### 두 가지 실행 모드

#### 모드 1: Kafka Connect 위에서 실행 (프로덕션)

```
MongoDB ──── Debezium Connector ──── Kafka Connect Worker ──── Kafka Topic
             (플러그인)              (프로세스)
```

Kafka Connect는 커넥터를 관리하는 프레임워크다. 커넥터는 REST API로 등록·삭제한다.
`quay.io/debezium/connect` 이미지는 Debezium MongoDB 커넥터가 내장된 Kafka Connect 이미지다.

#### 모드 2: Embedded Engine (테스트)

```
Test JVM
  └── DebeziumEngine (Debezium 라이브러리를 직접 임베드)
       └── MongoDB 변경을 in-memory 핸들러로 수신
```

`DebeziumCaptureTest`가 이 방식을 사용한다. Kafka 없이 Debezium 동작만 격리 테스트한다.

### snapshot.mode

커넥터가 처음 시작할 때 기존 데이터를 어떻게 처리할지 결정한다.

| 모드 | 동작 |
|------|------|
| `initial` | 기존 문서를 전부 스냅샷한 뒤 change stream 구독 |
| `no_data` | 스냅샷 없이 새 변경부터 구독 (테스트에서 사용) |
| `never` | 항상 change stream만 구독 (재시작 시 스냅샷 없음) |

### capture.mode

UPDATE 이벤트가 발생했을 때 value에 무엇을 담을지 결정한다.

| 모드 | 동작 |
|------|------|
| `change_streams` | 변경된 필드만 포함 |
| `change_streams_update_full` | 전체 문서 포함 (이 프로젝트에서 사용) |

`change_streams_update_full`을 쓰는 이유: Kafka Streams에서 enrichment할 때 전체 문서가 필요하기 때문이다.
변경된 필드만 있으면 파생 뷰를 제대로 만들 수 없다.

---

## 3. ExtractNewDocumentState SMT

SMT(Single Message Transform)는 Kafka Connect가 메시지를 Kafka에 쓰기 직전에
메시지를 변환하는 플러그인이다.

### Debezium raw 이벤트의 문제

SMT 없이 Debezium이 발행하는 이벤트는 Debezium 내부 봉투(envelope) 구조다.

```json
// SMT 없을 때 value
{
  "schema": { ... },
  "payload": {
    "before": null,
    "after": { "_id": "order-1", "status": "PENDING", ... },
    "source": { "collection": "orders", "ts_ms": 1700000000000, ... },
    "op": "c"
  }
}
```

이 구조는 Kafka Streams에서 쓰기가 매우 불편하다. `payload.after._id`, `payload.after.status`...
enrichment 로직이 복잡해지고, 삭제 이벤트 처리도 별도 로직이 필요하다.

### ExtractNewDocumentState 적용 후

```json
// SMT 적용 후 value (schemas.enable=false)
{
  "_id": "order-1",
  "status": "PENDING",
  "totalAmount": 99000,
  "__deleted": "true"   ← DELETE 이벤트 시에만 추가됨
}
```

value가 flat document가 된다. `delete.tombstone.handling.mode=rewrite`를 설정하면
DELETE 이벤트에 `__deleted: "true"` 필드가 추가된다.

### key의 변환

SMT는 key도 변환한다. MongoDB의 `_id` 필드를 key로 추출하면서 필드명을 바꾼다.

```json
// SMT 적용 후 key (schemas.enable=false)
{ "id": "order-1" }   ← _id가 id로 변환됨 (underscore 제거)
```

이 때문에 `EnrichmentTopology.extractId()`는 `id` 필드를 먼저 읽고, 없으면 `_id`로 폴백한다.

```kotlin
private fun extractId(keyJson: String): String =
    try {
        val node = mapper.readTree(keyJson)
        val id = node.path("id").asText()          // SMT 적용 시
        if (id.isNotEmpty()) id
        else node.path("_id").asText().ifEmpty { keyJson }  // SMT 미적용 시 폴백
    } catch (e: Exception) { keyJson }
```

### DELETE 처리 흐름

```
MongoDB DELETE
    └→ Debezium raw: op="d", after=null
        └→ SMT(rewrite): value에 __deleted=true 추가 (tombstone 대신 일반 메시지)
            └→ OrderEnricher.isDeleted() → true
                └→ context.forward(record.withValue(null))  ← Kafka tombstone 발행
                    └→ ES Sink: behavior.on.null.values=delete → ES에서 문서 삭제
```

---

## 4. Kafka Connect Converter

Converter는 Kafka Connect의 내부 데이터 구조를 Kafka 토픽의 바이트로 직렬화하는 컴포넌트다.

### Worker 레벨 vs 커넥터 레벨

Converter 설정은 두 곳에서 할 수 있다.

```
Worker 레벨 (Connect 프로세스 전체 기본값)
  └── docker-compose의 env var: KEY_CONVERTER, VALUE_CONVERTER, ...
      └── 단, Debezium 이미지는 VALUE_CONVERTER_SCHEMAS_ENABLE 등
          세부 속성을 env var로 인식하지 않음

커넥터 레벨 (개별 커넥터 config에서 override)
  └── connector JSON: "key.converter.schemas.enable": "false"
      └── Worker 레벨 설정을 override함 ← 반드시 여기서 설정
```

이 프로젝트에서 Converter 설정을 커넥터 JSON에 직접 넣는 이유다.

### schemas.enable의 의미

`JsonConverter`는 기본적으로 `schemas.enable=true`다.

```json
// schemas.enable=true (기본값) — 쓰기 불편
{
  "schema": { "type": "struct", "fields": [...] },
  "payload": { "_id": "order-1", "status": "PENDING" }
}

// schemas.enable=false — flat JSON, 이 프로젝트에서 사용
{ "_id": "order-1", "status": "PENDING" }
```

`schemas.enable=false`를 쓰는 이유: Kafka Streams에서 `node.path("status")`처럼 직접 접근하기 위해.
Schema Registry를 쓰지 않는 JSON 파이프라인에서는 schemas.enable=false가 표준 선택이다.

---

## 5. Kafka Streams 핵심 개념

### Topology

Kafka Streams는 **스트림 처리 토폴로지**를 정의한다.
토폴로지는 입력 토픽부터 출력 토픽까지의 처리 그래프다.

```
Source(CDC 토픽)
  └── .map(key 추출)
      └── .processValues(enricher)
          └── Sink(enriched 토픽)
```

`StreamsBuilder`로 토폴로지를 선언적으로 정의하고, `KafkaStreams`가 이를 실행한다.

### KStream

무한한 레코드 스트림. 각 레코드는 독립적인 이벤트다.

```kotlin
builder.stream<String, String>("orders-cdc.forder.orders")
    .map { key, value -> ... }
    .to("enriched-orders")
```

이 프로젝트에서 CDC 토픽을 소비하는 데 사용한다.

### KTable

토픽을 **테이블**로 본다. 같은 key의 마지막 값이 현재 상태다.

```
레코드 시퀀스:
  key="order-1", value={status:"PENDING"}
  key="order-1", value={status:"APPROVED"}  ← 이 값이 현재 상태
```

KTable은 **파티션 단위**로 관리된다. 애플리케이션 인스턴스가 여러 개면,
각 인스턴스는 자신이 담당하는 파티션의 데이터만 가진다.

### GlobalKTable

**모든 파티션의 데이터를 전체 복제**하는 테이블. 각 인스턴스가 전체 데이터를 가진다.

```
KTable (파티션 단위)                   GlobalKTable (전체 복제)
  인스턴스A: partition 0, 1              인스턴스A: partition 0, 1, 2
  인스턴스B: partition 2                 인스턴스B: partition 0, 1, 2
```

---

## 6. GlobalKTable — 왜 KTable이 아닌가

### 문제: Foreign-key lookup

주문 enrichment에서 payment 정보를 조회해야 한다.

```
orders-cdc 토픽의 레코드:
  key = "order-1"          ← 주문 ID
  value = {paymentTxId: "pay-1", ...}

payments-store의 레코드:
  key = "pay-1"            ← 결제 ID  ← 주문 키와 다름!
```

주문의 key(`order-1`)와 결제의 key(`pay-1`)가 다르다. 이것이 **foreign-key join** 이다.

### KTable로 foreign-key join하면

KTable join은 **같은 파티션 번호**에 있는 레코드끼리만 조인한다.
(`order-1`과 `pay-1`은 대부분 다른 파티션에 있다)

```kotlin
// KTable join은 co-partitioned 필요 — 동작하지 않음
ordersStream.join(paymentsTable) { order, payment -> ... }
// key가 다르면 조인이 안 됨
```

### GlobalKTable은 어떻게 해결하는가

GlobalKTable은 모든 인스턴스에 전체 데이터가 있으므로,
어느 파티션의 레코드든 GlobalKTable에서 임의의 key로 lookup할 수 있다.

```kotlin
// GlobalKTable lookup — 파티션 무관
val paymentJson: String? = paymentsStore.get("pay-1")  // 항상 찾을 수 있음
```

`OrderEnricher`가 이 방식으로 동작한다.

```kotlin
val paymentTxId = order.path("paymentTxId").asText()
paymentsStore?.get(paymentTxId)?.let { paymentJson ->
    val payment = mapper.readTree(paymentJson)
    result.put("paymentMethod", payment.path("method").asText())
}
```

### GlobalKTable의 trade-off

| | KTable | GlobalKTable |
|--|--------|--------------|
| 파티션 | 분산 | 모든 인스턴스에 전체 복제 |
| 메모리 | 인스턴스당 1/N | 인스턴스당 전체 |
| Lookup | 같은 key만 | 임의의 key |
| 사용 경우 | 같은 key join | foreign-key lookup |

이 프로젝트에서 products, payments, deliveries를 GlobalKTable로 쓰는 이유:
주문에서 다른 엔티티를 **ID 기반 lookup** 해야 하기 때문이다.

---

## 7. 이 프로젝트의 enrichment 아키텍처

### 전체 데이터 흐름

```
MongoDB (각 서비스의 DB)
  │
  ├── orders 컬렉션     ──→ orders-cdc.forder.orders     ──→ [OrderEnricher]    ──→ enriched-orders
  ├── payments 컬렉션   ──→ payments-cdc.forder.payments  ──→ [PaymentEnricher]  ──→ enriched-payments
  ├── deliveries 컬렉션 ──→ deliveries-cdc.forder.deliveries ──→ [DeliveryEnricher] ──→ enriched-deliveries
  ├── inventory 컬렉션  ──→ inventory-cdc.forder.inventory ──→ [InventoryEnricher] ──→ enriched-inventory
  └── products 컬렉션   ──→ products-cdc.forder.products  ──→ [ProductEnricher]  ──→ enriched-products
                                                                                          │
                                                                               ┌──────────┘
                                                                               │ GlobalKTable로 읽힘
                                                                               └──→ OrderEnricher에서
                                                                                    payment/delivery/product lookup
```

### Self-referential GlobalKTable

이 아키텍처의 특이한 점: **enriched-* 토픽이 동시에 출력(sink)이자 GlobalKTable의 소스(source)다.**

```
enriched-products 토픽
  ├── ProductEnricher가 씀 (sink)
  └── OrderEnricher / InventoryEnricher가 GlobalKTable로 읽음 (source)
```

Kafka Streams에서 이것이 가능한 이유:
- `.to()` (regular sink) ≠ `builder.globalTable()` (source)
- 같은 토픽을 다른 오퍼레이션으로 참조하면 토폴로지 충돌이 없다.

### OrderEnricher가 하는 일

```
CDC value (flat JSON from MongoDB)
  {_id, userId, status, totalAmount, items, paymentTxId, deliveryId, ...}
                    │
                    ▼
              OrderEnricher
                    │
        ┌───────────┼───────────────┐
        │           │               │
   paymentsStore  deliveriesStore  productsStore
   (GlobalKTable)  (GlobalKTable)  (GlobalKTable)
   get(paymentTxId) get(deliveryId) get(productId per item)
        │           │               │
        └───────────┼───────────────┘
                    ▼
         enriched-orders value
  {orderId, status, totalAmount, paymentMethod,  ← payment에서
   trackingNumber, deliveryStatus,               ← delivery에서
   items: [{productName, sellerName, ...}]}      ← product에서
```

### Elasticsearch Sink

enriched-* 토픽의 데이터가 Elasticsearch로 싱크된다.

```
enriched-orders ──→ [ES Sink Connector] ──→ Elasticsearch index: orders-view
                                              └── query-service가 검색에 사용
```

ES Sink의 주요 설정:
- `write.method=upsert` — 같은 document_id면 업서트
- `behavior.on.null.values=delete` — Kafka tombstone(value=null) 수신 시 ES에서 삭제
- `key.ignore=false` — Kafka 레코드의 key가 ES document의 `_id`가 됨

---

## 8. 설계 결정 근거

### 왜 CDC + Kafka Streams인가? (CQRS Read Model 구축)

이 프로젝트는 CQRS 패턴을 쓴다.
- **Write side**: 각 마이크로서비스의 MongoDB
- **Read side**: Elasticsearch (query-service)

읽기 모델에는 여러 서비스의 데이터가 합쳐진 뷰가 필요하다.
예: 주문 조회 시 결제 방법, 배송 추적 번호도 같이 보여줘야 한다.

```
// 읽기에 필요한 데이터 (여러 서비스에 분산됨)
{
  orderId: "order-1",       ← order-service
  status: "DELIVERED",      ← order-service
  paymentMethod: "CARD",    ← payment-service
  trackingNumber: "CJ-123", ← delivery-service
  items: [{
    productName: "에어팟",   ← product-service
    quantity: 1
  }]
}
```

이걸 동기 API 호출로 조합하면:
1. **latency**: N번의 마이크로서비스 호출
2. **coupling**: 읽기마다 다른 서비스에 의존
3. **availability**: 하나라도 다운되면 읽기 실패

CDC + Kafka Streams는 이 문제를 **비동기 사전 조합**으로 해결한다.
각 서비스의 변경이 Kafka를 통해 흘러오면 Streams 앱이 합쳐서 ES에 저장해 둔다.

### 왜 Debezium이지 직접 Kafka 발행이 아닌가? (Outbox Pattern)

Order-service에서는 **Outbox Pattern**을 사용한다.

```kotlin
@Transactional
suspend fun createOrder(...) {
    orderRepository.save(order)     // MongoDB 트랜잭션
    outboxRepository.save(event)    // 같은 트랜잭션
}
// Debezium이 outbox 컬렉션 변경을 감지 → Kafka에 자동 발행
```

이렇게 하면:
- 두 저장 연산이 하나의 MongoDB 트랜잭션 → **atomic**
- Debezium이 보장: DB에 기록된 것은 반드시 Kafka에 발행됨 → **exactly-once 발행**

### 왜 GlobalKTable의 소스를 enriched-* 토픽으로 쓰는가?

자연스러운 대안은 CDC 토픽을 GlobalKTable 소스로 쓰는 것이다.

```kotlin
// 대안: CDC 토픽을 GlobalKTable 소스로 직접 사용
builder.globalTable("payments-cdc.forder.payments", ...)  // CDC raw 포맷
```

이 방식의 문제:
1. GlobalKTable에 CDC 봉투 구조(before/after/op/source...)가 그대로 들어감
2. lookup 시마다 봉투 구조를 파싱해야 함
3. enrichment 로직이 Processor 안에 뭉쳐짐

현재 설계(enriched-* 토픽을 GlobalKTable 소스로 사용):
1. CDC → enrichment 단계에서 필요한 필드만 추출·정제
2. GlobalKTable에는 깔끔한 enriched 포맷만 저장
3. lookup 시 바로 사용 가능 (`payment.path("method")` 등)
4. enriched 토픽 자체가 ES sink의 입력이므로, 한 번의 처리로 두 용도에 사용

---

## 9. 트러블슈팅 레퍼런스

### TC 2.0 + MongoDB Replica Set

Testcontainers 2.0의 `MongoDBContainer`는 Replica Set을 자동 설정하지 않는다.
Debezium change stream은 Replica Set이 필수이므로 `GenericContainer`로 직접 구성해야 한다.

```kotlin
// ❌ TC 2.0에서 change stream 불가
val mongo = MongoDBContainer("mongo:latest")

// ✅ 직접 구성
val mongo = GenericContainer("mongo:latest")
    .withCommand("mongod", "--replSet", "rs0", "--bind_ip_all")

@BeforeAll
fun initReplicaSet() {
    mongo.execInContainer("mongosh", "--quiet", "--eval",
        """rs.initiate({_id:"rs0",members:[{_id:0,host:"<member-host>:<port>"}]})""")
    Thread.sleep(3_000)  // Primary election 대기
}
```

member hostname 설정 기준:

| 상황 | member hostname |
|------|----------------|
| `DebeziumCaptureTest` (embedded engine, host JVM에서 연결) | `host.docker.internal:<mappedPort>` |
| `KafkaStreamsPipelineE2ETest` (Connect 컨테이너에서 연결, 같은 Docker network) | `mongo:27017` (network alias) |

### MongoDB Driver 5.x URI 제약

`directConnection=true`와 `replicaSet=rs0`를 동시에 쓸 수 없다 (URI spec 위반).

```
// ❌
mongodb://localhost:27017/?directConnection=true&replicaSet=rs0

// ✅ Debezium용 (replica set discovery 필요)
mongodb://localhost:27017/?replicaSet=rs0

// ✅ 테스트 CRUD 헬퍼용 (단순 쓰기, discovery 불필요)
mongodb://localhost:27017/?directConnection=true
```

### Debezium 버전 호환성

| Debezium 버전 | Kafka Connect 기반 | Kafka 4.0 호환 |
|-----------|-------------------|----------------|
| 3.0.x | Kafka Connect 3.8.0 | ❌ |
| 3.4.x | Kafka Connect 4.1.1 | ✅ |

Debezium 3.0+부터 Docker Hub 이미지 배포 중단 → `quay.io/debezium/connect` 사용.

### Converter 설정 위치

Worker 레벨 env var(`VALUE_CONVERTER_SCHEMAS_ENABLE`)는 Debezium Docker 이미지에서 인식되지 않는다.
반드시 **커넥터 config JSON**에 직접 설정해야 한다.

```json
// ✅ 커넥터 config에서 설정
{
  "key.converter": "org.apache.kafka.connect.json.JsonConverter",
  "key.converter.schemas.enable": "false",
  "value.converter": "org.apache.kafka.connect.json.JsonConverter",
  "value.converter.schemas.enable": "false"
}
```

### GlobalKTable 소스 토픽 사전 생성

KafkaStreams는 시작 시 GlobalKTable 소스 토픽이 반드시 존재해야 한다.
토픽이 없으면 `MissingSourceTopicException`이 발생한다.

```kotlin
// KafkaStreams 시작 전에 반드시 토픽 생성
private fun createTopics() {
    AdminClient.create(...).use { admin ->
        val topics = listOf(
            EnrichmentTopology.ENRICHED_PRODUCTS,   // GlobalKTable 소스
            EnrichmentTopology.ENRICHED_PAYMENTS,   // GlobalKTable 소스
            EnrichmentTopology.ENRICHED_DELIVERIES, // GlobalKTable 소스
            EnrichmentTopology.ENRICHED_ORDERS,     // 출력 토픽 (consumer 발견 지연 방지)
            // ... CDC 토픽들
        ).map { NewTopic(it, 1, 1.toShort()) }
        admin.createTopics(topics).all().get(30, TimeUnit.SECONDS)
    }
}
```

출력 토픽도 사전 생성하는 이유: KafkaConsumer가 존재하지 않는 토픽을 구독할 경우
토픽 자동 생성 후 파티션 재할당까지 최대 `metadata.max.age.ms`(기본 5분)가 걸릴 수 있다.

### SMT key 필드명 변환

`ExtractNewDocumentState` SMT는 MongoDB `_id` 필드를 key에서 `id`로 변환한다.
(underscore 제거)

```
MongoDB _id: "order-1"
  └→ CDC key (SMT 적용 후, schemas.enable=false): {"id": "order-1"}
  └→ CDC value (SMT 적용 후, schemas.enable=false): {"_id": "order-1", "status": ...}
```

value에서는 `_id`가 그대로 유지된다. key만 변환된다.
`extractId()`에서 `id`를 먼저 읽고 `_id`로 폴백하는 이유다.
