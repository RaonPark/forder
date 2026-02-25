# 단위 테스트 가이드

> `InventoryServiceTest`, `InventoryCommandHandlerTest` 작성 과정에서 적용한 기법과 이론을 정리한 문서입니다.

---

## 목차

1. [단위 테스트란 무엇인가](#1-단위-테스트란-무엇인가)
2. [왜 MockK인가 — Mockito와의 차이](#2-왜-mockk인가--mockito와의-차이)
3. [MockK 핵심 API](#3-mockk-핵심-api)
4. [코루틴 테스트 — runTest](#4-코루틴-테스트--runtest)
5. [Flow 테스트](#5-flow-테스트)
6. [테스트 구조 설계](#6-테스트-구조-설계)
7. [픽스처 패턴 — 테스트 데이터 관리](#7-픽스처-패턴--테스트-데이터-관리)
8. [예외 테스트 — 코루틴에서의 올바른 방법](#8-예외-테스트--코루틴에서의-올바른-방법)
9. [무엇을 검증해야 하는가 — 상태 vs 행동](#9-무엇을-검증해야-하는가--상태-vs-행동)
10. [각 테스트 케이스별 설계 의도](#10-각-테스트-케이스별-설계-의도)

---

## 1. 단위 테스트란 무엇인가

### 정의

단위 테스트(Unit Test)는 **하나의 클래스 또는 함수를 외부 의존성 없이 독립적으로 검증**하는 테스트다.
`InventoryServiceTest`의 경우 `InventoryService`만 실제 객체로 두고, `InventoryRepository`와 `InventoryHistoryRepository`는 가짜(Mock) 객체로 대체한다.

```
[실제 객체]            [Mock 객체]
InventoryService  →  InventoryRepository      (가짜)
                  →  InventoryHistoryRepository (가짜)
```

### FIRST 원칙

좋은 단위 테스트가 갖춰야 할 5가지 속성이다.

| 원칙 | 의미 | 우리 테스트에서 |
|------|------|----------------|
| **F**ast | 빠르게 실행 | DB/Kafka 없이 순수 메모리에서 실행 |
| **I**ndependent | 독립적 실행 | 각 테스트가 다른 테스트에 영향 없음 |
| **R**epeatable | 반복 가능 | Mock을 사용하므로 환경에 무관 |
| **S**elf-validating | 자동 검증 | assert로 pass/fail 자동 판단 |
| **T**imely | 코드 작성 시점에 | 구현 직후 작성 |

### AAA 패턴

모든 테스트는 세 구역으로 나뉜다.

```kotlin
@Test
fun `양수 delta로 입고 처리 성공`() = runTest {
    // Arrange (준비) — Mock 설정, 테스트 데이터 생성
    val inv = inventory(currentStock = 50)
    coEvery { inventoryRepository.findById("inv-1") } returns inv
    coEvery { inventoryRepository.save(any()) } returns inv.copy(currentStock = 80)
    coEvery { inventoryHistoryRepository.save(any()) } returns mockk()

    // Act (실행) — 테스트 대상 함수 호출
    val response = inventoryService.adjustStock("inv-1", adjustRequest(quantityDelta = 30))

    // Assert (검증) — 결과 확인
    assertEquals(80, response.currentStock)
    coVerify { inventoryHistoryRepository.save(match { it.changeType == InventoryHistoryChangeType.STOCK_IN }) }
}
```

---

## 2. 왜 MockK인가 — Mockito와의 차이

### Mockito의 한계

Spring Boot의 기본 테스트 라이브러리인 Mockito는 Java 기반이라 Kotlin과 함께 사용할 때 몇 가지 문제가 있다.

**① Kotlin 클래스는 기본적으로 final**

Kotlin의 모든 클래스는 `open` 키워드 없이는 final이다. Mockito는 기본적으로 final 클래스를 mock할 수 없다.

```kotlin
// Kotlin에서 이건 final 클래스
class InventoryService(...) { ... }

// Mockito → 별도 설정(MockitoExtensions) 없으면 오류 발생
val mock = Mockito.mock(InventoryService::class.java) // ❌
```

**② suspend 함수를 자연스럽게 mock할 수 없음**

`suspend fun`은 컴파일 후 `Continuation` 파라미터가 추가된 Java 메서드로 변환된다. Mockito는 이 시그니처를 직접 다루기 어렵다.

```kotlin
// Mockito-kotlin을 써도 when/given 구문이 어색하고 제약이 많다
whenever(repo.findById(any())).thenReturn(inventory) // suspend 함수엔 문제 발생
```

### MockK의 장점

MockK는 Kotlin을 위해 만들어진 라이브러리다.

```kotlin
// suspend 함수 → coEvery / coVerify
coEvery { inventoryRepository.findById(any()) } returns inventory

// 일반 함수 → every / verify
every { inventoryRepository.findAllByProductId(any()) } returns flowOf(inventory)

// final 클래스도 mock 가능 (Protobuf 생성 클래스 포함)
val command = mockk<InventoryCommand> { ... }
```

---

## 3. MockK 핵심 API

### 3.1 Mock 객체 생성

```kotlin
// 방법 1: 어노테이션 (추천 — 클래스 수준 의존성 관리)
@MockK
lateinit var inventoryRepository: InventoryRepository

@InjectMockKs          // @MockK 필드를 생성자 주입으로 대상 객체에 주입
lateinit var inventoryService: InventoryService

// 방법 2: 직접 생성
val mock = mockk<InventoryRepository>()

// 방법 3: relaxed mock (모든 함수가 기본값 반환 — 반환값 검증이 필요 없을 때)
val ack = mockk<Acknowledgment>(relaxed = true)
```

**`relaxed = true`를 쓴 이유**
`Acknowledgment.acknowledge()`는 `Unit`을 반환하고 반환값을 검증할 필요가 없다.
일반 mock에서 Unit 반환 함수는 `just runs`로 설정해야 하지만, relaxed mock은 자동으로 처리한다.

### 3.2 Stubbing — 행동 정의

```kotlin
// 일반 함수 (Flow, List 등 반환)
every { repo.findAllByProductId("prod-1") } returns flowOf(inv)

// suspend 함수 — co 접두사 필수
coEvery { repo.findById("inv-1") } returns inv

// Unit 반환 suspend 함수
coEvery { repo.deleteById("inv-1") } just runs

// 어떤 인자도 허용
coEvery { repo.findById(any()) } returns inv

// 특정 조건의 인자만
coEvery { repo.findById(match { it.startsWith("inv") }) } returns inv

// 예외 던지기
coEvery { repo.findById("bad") } throws RuntimeException("DB error")
```

### 3.3 Verification — 호출 검증

```kotlin
// 정확히 1번 호출됐는지 (기본값)
coVerify(exactly = 1) { repo.deleteById("inv-1") }

// 한 번도 호출되지 않았는지
coVerify(exactly = 0) { repo.save(any()) }

// 인자 조건으로 검증
coVerify {
    historyRepo.save(match { it.changeType == InventoryHistoryChangeType.STOCK_IN })
}

// 일반 함수 검증
verify(exactly = 1) { ack.acknowledge() }
```

### 3.4 Slot & Capture — 인자 캡처

`slot`은 mock에 전달된 실제 인자를 꺼내서 검증할 수 있게 해준다.

```kotlin
val capturedInventory = slot<Inventory>()

coEvery { inventoryRepository.save(capture(capturedInventory)) } returns inventory()

inventoryService.createInventory(request)

// 실제로 save()에 넘어간 객체를 검증
with(capturedInventory.captured) {
    assertEquals("prod-1", productId)
    assertEquals(0, reservedStock)   // 항상 0으로 초기화됐는지
    assertTrue(inventoryId.isNotBlank())  // UUID가 생성됐는지
}
```

**`any()` vs `capture(slot)` 차이**

| | `any()` | `capture(slot)` |
|-|---------|-----------------|
| 목적 | "어떤 값이든 받아라" | "받은 값을 꺼내서 직접 검증" |
| 검증 가능 | 호출 여부만 | 전달된 객체의 내부 상태 |
| 언제 | 반환값만 테스트할 때 | 입력 매핑 로직을 테스트할 때 |

`createInventory` 테스트에서 `slot`을 사용한 이유:
서비스가 `CreateInventoryRequest`를 `Inventory` 도큐먼트로 올바르게 변환하는지 — 즉 **매핑 로직 자체**를 검증하기 위해서다.
`any()`만 쓰면 "save가 호출됐다"는 사실만 알 수 있고, "어떤 객체가 저장됐는지"는 알 수 없다.

---

## 4. 코루틴 테스트 — runTest

### suspend 함수는 왜 일반 테스트에서 실행할 수 없는가

Kotlin의 `suspend` 함수는 코루틴 컨텍스트 안에서만 실행될 수 있다. 일반 JUnit 테스트 함수(`fun`)는 코루틴이 아니므로 직접 suspend 함수를 호출하면 컴파일 에러가 난다.

```kotlin
@Test
fun `이건 안 됨`() {
    inventoryService.getInventory("inv-1") // ❌ 컴파일 에러: suspend 함수는 코루틴 안에서만 호출 가능
}
```

### runTest

`kotlinx-coroutines-test` 라이브러리의 `runTest`는 테스트용 코루틴 스코프를 제공한다.

```kotlin
@Test
fun `이건 됨`() = runTest {               // runTest가 코루틴 컨텍스트를 만들어 줌
    val result = inventoryService.getInventory("inv-1")  // ✅ 정상 호출
    assertEquals("inv-1", result.inventoryId)
}
```

**`runTest` vs `runBlocking`**

| | `runTest` | `runBlocking` |
|-|-----------|---------------|
| 용도 | 테스트 전용 | 일반 코드에서 코루틴 실행 |
| 가상 시간 | 지원 (delay를 즉시 스킵) | 미지원 (실제 시간 소요) |
| 테스트 권장 | ✅ | ❌ (테스트에서 사용하면 안 됨) |

`delay(1000L)`이 있는 코드도 `runTest` 안에서는 실제로 1초를 기다리지 않는다.
가상 시간(virtual time)이 즉시 앞으로 이동하기 때문이다.

---

## 5. Flow 테스트

### Flow를 반환하는 함수의 Mock

`Flow`를 반환하는 함수는 **suspend 함수가 아니다**. 따라서 `every`를 사용한다.

```kotlin
// InventoryRepository
fun findAllByProductIdAndOptionId(productId: String, optionId: String?): Flow<Inventory>
// → suspend 아님 → every 사용

// 올바른 Mock
every {
    inventoryRepository.findAllByProductIdAndOptionId("prod-1", "opt-1")
} returns flowOf(inv)

// 잘못된 Mock (컴파일은 되지만 의미가 다름)
coEvery {   // ← 불필요한 co 접두사
    inventoryRepository.findAllByProductIdAndOptionId("prod-1", "opt-1")
} returns flowOf(inv)
```

`flowOf(inv)`는 `inv` 하나만 emit하고 완료되는 Flow를 생성한다.
`firstOrNull { 조건 }`이 이 Flow를 소비하면서 조건에 맞는 첫 번째 원소를 꺼낸다.

### Flow Terminal Operator는 suspend

`Flow`를 소비하는 terminal operator(`collect`, `first`, `firstOrNull`, `toList` 등)는 suspend 함수다.
이 때문에 `reserveStock`과 `releaseStock`은 전체가 suspend 함수여야 한다.

```
findAllByProductIdAndOptionId() → Flow<Inventory>   ← 비-suspend, lazy
    .firstOrNull { ... }                            ← suspend, 실제 소비 발생
```

---

## 6. 테스트 구조 설계

### @Nested 클래스로 그룹화

```kotlin
class InventoryServiceTest {

    @Nested inner class CreateInventory {
        @Test fun `신규 재고 생성 성공`() = runTest { ... }
        @Test fun `중복 등록 시 CONFLICT`() = runTest { ... }
    }

    @Nested inner class ReserveStock {
        @Test fun `재고 충분하면 true 반환`() = runTest { ... }
        @Test fun `재고 부족하면 false 반환`() = runTest { ... }
    }
}
```

**`@Nested`를 쓴 이유**

- 테스트 리포트에서 `CreateInventory > 신규 재고 생성 성공` 형태로 계층이 보임
- 같은 메서드를 테스트하는 케이스들이 명확히 묶임
- `inner class`이므로 외부 클래스의 Mock 필드를 공유할 수 있음

**`inner class`가 필요한 이유**
`@Nested` 클래스가 `inner`가 아니면 외부 클래스의 인스턴스에 접근할 수 없다.
`@MockK` 필드들은 외부 클래스에 있기 때문에 `inner`가 필수다.

### @ExtendWith(MockKExtension::class)

MockK의 JUnit 5 확장이다. 이 어노테이션이 있어야:
- `@MockK` 어노테이션이 달린 필드에 자동으로 Mock 객체가 주입됨
- `@InjectMockKs` 어노테이션이 달린 필드에 Mock들이 생성자 주입됨
- 각 테스트 전후로 Mock 상태가 자동 초기화됨

```kotlin
@ExtendWith(MockKExtension::class)   // 이 한 줄로 아래 어노테이션들이 동작
class InventoryServiceTest {
    @MockK lateinit var inventoryRepository: InventoryRepository
    @InjectMockKs lateinit var inventoryService: InventoryService
}
```

---

## 7. 픽스처 패턴 — 테스트 데이터 관리

### 문제: 테스트마다 객체 생성 코드 반복

```kotlin
// 중복이 많고 변경에 취약한 방식
@Test fun `테스트 A`() = runTest {
    val inv = Inventory("inv-1", "prod-1", "WAREHOUSE_A", null, 100, 0, 10, Instant.now(), Instant.now(), 0L)
    ...
}

@Test fun `테스트 B`() = runTest {
    val inv = Inventory("inv-1", "prod-1", "WAREHOUSE_A", null, 50, 0, 10, Instant.now(), Instant.now(), 0L)
    //                                                          ↑ 이것만 다름
    ...
}
```

`Inventory` 생성자에 필드가 하나 추가되면 모든 테스트를 수정해야 한다.

### 해결: Default Parameter가 있는 픽스처 함수

```kotlin
private fun inventory(
    inventoryId: String = "inv-1",
    productId: String = "prod-1",
    currentStock: Int = 100,      // ← 테스트별로 바꾸고 싶은 것만 오버라이드
    reservedStock: Int = 0,
    // ...
) = Inventory(...)
```

```kotlin
// 사용하는 곳: 필요한 것만 명시
val inv = inventory(currentStock = 5)          // 재고 부족 시나리오
val inv = inventory(currentStock = 10, reservedStock = 8)  // 예약 재고 시나리오
```

**장점**
- 생성자 변경 시 픽스처 함수 하나만 수정하면 됨
- 테스트 코드에서 "이 테스트에서 중요한 값"이 무엇인지 명확히 드러남

---

## 8. 예외 테스트 — 코루틴에서의 올바른 방법

### 왜 `assertThrows`를 쓰지 않았는가

JUnit의 `assertThrows`는 일반 람다(`() -> Unit`)를 받는다.
하지만 `suspend` 함수는 일반 람다에서 직접 호출할 수 없다.

```kotlin
// ❌ 컴파일 에러
assertThrows<ResponseStatusException> {
    inventoryService.getInventory("no-such")  // suspend 함수
}
```

### runCatching 패턴

`runCatching`은 suspend 람다를 지원하는 Kotlin 표준 함수다.

```kotlin
// ✅ runTest 안에서 suspend 함수의 예외를 안전하게 캡처
val result = runCatching { inventoryService.getInventory("no-such") }

assertIs<ResponseStatusException>(result.exceptionOrNull())
assertEquals(HttpStatus.NOT_FOUND, (result.exceptionOrNull() as ResponseStatusException).statusCode)
```

**흐름**
1. `runCatching { }` — 내부에서 예외 발생 시 `Result.Failure`로 래핑
2. `result.exceptionOrNull()` — 발생한 예외 객체 추출 (없으면 null)
3. `assertIs<T>()` — 예외 타입 검증 (동시에 스마트 캐스트)
4. `statusCode` — HTTP 상태 코드 검증

---

## 9. 무엇을 검증해야 하는가 — 상태 vs 행동

테스트에서 검증할 수 있는 것은 두 가지다.

### 상태 검증 (State Verification)

함수 실행 후 반환값이나 객체의 상태를 확인한다.

```kotlin
val response = inventoryService.adjustStock("inv-1", adjustRequest(30))

assertEquals(80, response.currentStock)   // 반환값 검증
```

### 행동 검증 (Behavior Verification)

특정 협력 객체의 메서드가 예상대로 호출됐는지 확인한다.

```kotlin
coVerify {
    inventoryHistoryRepository.save(
        match { it.changeType == InventoryHistoryChangeType.STOCK_IN }
    )
}
```

### 어떤 것을 선택할까

**상태 검증 우선 원칙**: 가능하면 상태 검증을 우선한다.
행동 검증은 구현 세부사항에 결합도가 높아 리팩토링 시 테스트가 깨지기 쉽다.

그러나 우리 코드에서 행동 검증이 필요한 경우:

| 상황 | 이유 |
|------|------|
| `inventoryHistoryRepository.save()` 검증 | 이력 저장은 부수 효과라 반환값에 드러나지 않음 |
| `ack.acknowledge()` 검증 | Kafka offset 커밋 — 비즈니스 불변조건 |
| `repo.save()` 미호출 검증 | 재고 부족 시 DB를 건드리지 않음을 보장 |
| `kafkaTemplate.send()` 검증 | Reply 메시지 발행 — 이것이 핵심 출력 |

**`match { }` 를 쓰는 이유**
단순히 "save가 호출됐다"가 아니라 "올바른 changeType으로 저장됐다"를 검증하기 위해서다.
틀린 changeType으로 저장돼도 `coVerify { repo.save(any()) }`는 통과한다.

---

## 10. 각 테스트 케이스별 설계 의도

### InventoryServiceTest

#### `createInventory` — slot capture 사용

```kotlin
val capturedInventory = slot<Inventory>()
coEvery { inventoryRepository.save(capture(capturedInventory)) } returns inventory()

inventoryService.createInventory(request)

with(capturedInventory.captured) {
    assertEquals(100, currentStock)    // initialStock → currentStock 매핑
    assertEquals(0, reservedStock)     // 항상 0으로 초기화되는지
    assertTrue(inventoryId.isNotBlank()) // UUID가 실제로 생성됐는지
}
```

> `slot`을 쓴 이유: 반환값(response)이 아니라 **저장되는 객체의 매핑 로직**을 검증하는 것이 목적이다.
> `any()`만 쓰면 `reservedStock`이 100으로 잘못 설정돼도 테스트가 통과한다.

#### `adjustStock` — changeType 분기 검증

```kotlin
// 양수: STOCK_IN
coVerify { historyRepo.save(match { it.changeType == STOCK_IN }) }

// 음수: STOCK_OUT_MANUAL
coVerify { historyRepo.save(match { it.changeType == STOCK_OUT_MANUAL }) }
```

> `if (delta >= 0) STOCK_IN else STOCK_OUT_MANUAL` 분기가 올바르게 작동하는지 확인.
> 이 로직이 틀리면 이력이 잘못 기록돼 재고 추적이 불가능해진다.

#### `reserveStock` — 가용재고 계산식 검증

```kotlin
// currentStock=10, reservedStock=8 → 가용재고 2 < 요청 5 → false
val inv = inventory(currentStock = 10, reservedStock = 8)
```

> `currentStock - reservedStock >= quantity` 계산식을 검증.
> `currentStock`만 보는 실수를 방지한다 — 예약된 재고가 있으면 실제 사용 가능 수량은 더 적다.

#### `releaseStock` — minOf 방어 로직 검증

```kotlin
// reservedStock=5, 요청=20 → releasedQty = minOf(20, 5) = 5
coVerify { historyRepo.save(match { it.quantityDelta == -5 }) }
```

> 요청 수량이 예약 수량보다 많을 때 **음수로 내려가지 않도록 보호**하는 로직 검증.
> 사가 재처리(Kafka 재전송)로 인한 중복 해제 시 데이터 무결성 보장에 중요하다.

---

### InventoryCommandHandlerTest

#### Protobuf 객체 Mocking

```kotlin
val command = mockk<InventoryCommand> {
    every { this@mockk.sagaId } returns "saga-001"
    every { type } returns InventoryCommandType.RESERVE
    every { itemsList } returns listOf(item)
}
```

> Protobuf 생성 클래스는 final 클래스다. Mockito로는 mock이 어렵지만 MockK는 가능하다.
> 빌더 API로 실제 proto 객체를 생성하는 것도 가능하지만, 테스트 목적상 mock이 더 간결하다.

#### slot으로 Kafka Reply 내용 검증

```kotlin
val replySlot = slot<GeneratedMessage>()
verify { kafkaTemplate.send("inventory-reply", "saga-001", capture(replySlot)) }

val reply = replySlot.captured as InventoryReply
assertTrue(reply.success)
assertEquals("saga-001", reply.sagaId)
```

> `kafkaTemplate.send()`가 호출됐는지만이 아니라, **실제로 발행된 Reply 메시지의 내용**을 검증한다.
> `sagaId`가 잘못 들어가면 order-service가 어떤 사가의 응답인지 매핑하지 못한다.

#### ack.acknowledge() 검증

```kotlin
verify(exactly = 1) { ack.acknowledge() }
```

> 처리 성공/실패와 무관하게 **항상 Kafka offset을 커밋해야 한다**는 비즈니스 불변조건.
> ack를 빠뜨리면 같은 커맨드가 무한 재처리되는 무한루프가 발생한다.

---

## 참고

- [MockK 공식 문서](https://mockk.io/)
- [kotlinx-coroutines-test 공식 문서](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/)
- [JUnit 5 @Nested 가이드](https://junit.org/junit5/docs/current/user-guide/#writing-tests-nested)
