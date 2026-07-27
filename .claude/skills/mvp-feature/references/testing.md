# 테스트 규칙 — 단위 · 통합 · E2E

단계 5에서 읽는다.

세 층을 나누는 이유는 형식을 채우기 위해서가 아니라, **각 층이 잡을 수 있는 버그가 다르기 때문**이다.
같은 것을 세 번 검증하면 시간만 쓰고 아무것도 더 잡지 못한다. 층마다 다음을 맡는다.

| 층 | 잡는 것 | 스프링 컨텍스트 | DB |
|---|---|---|---|
| 단위 | 도메인 규칙, 상태 전이 거부, 계산식 | 없음 | 없음 |
| 통합 | 트랜잭션 경계, JPA 매핑, DB 제약, 동시성 | 있음 | H2(MySQL 모드) |
| E2E | 이슈의 「완료 조건」이 사용자 관점에서 성립하는가 | 있음 | H2(MySQL 모드) |

## 공통 관례

- JUnit 5 + AssertJ. `assertThat`, `assertThatThrownBy`.
- 테스트 메서드명은 **한글 스네이크 케이스**로 검증 내용을 문장처럼 쓴다. 기존 테스트가 그렇다.
  ```java
  @Test
  void 진행중_배송요청이_있으면_새_요청을_거부한다() { ... }
  ```
- 파일 위치는 대상 클래스와 같은 패키지: `src/test/java/com/turkey/quick/{도메인}/...`
- 클래스는 `class XxxTest`(public 불필요), 테스트 메서드도 package-private.
- 왜 이런 값을 썼는지 자명하지 않으면 주석으로 남긴다. 기존 `FarePolicyTest`가 좋은 예다
  (시계 해상도 때문에 `effectiveFrom`을 과거로 민 이유를 적어 뒀다).

## 단위 테스트

스프링을 띄우지 않는다. 순수 객체만 만들어서 검증한다. 빠르고, 항상 쓴다.

무엇을 쓸지 고를 때 기준: **이 규칙이 깨지면 데이터가 잘못되는가?**

- 생성 시 불변식 (금액 양수, 필수 값, 기간 역전 금지)
- 상태 전이 거부 — 허용되지 않은 전이가 예외로 막히는지. `CLAUDE.md`의 상태값 표를 그대로 케이스로 옮긴다.
- 계산 로직 (요금, 정산액, 포인트 증감)
- 경계값 — 0, 최대 거리, 정확히 같은 시각

```java
@Test
void 배차되지_않은_배송은_수령완료로_전이할_수_없다() {
    DeliveryOrder order = 배송요청_생성();   // WAITING

    assertThatThrownBy(order::pickUp)
            .isInstanceOf(IllegalStateException.class);
}
```

## 통합 테스트

여기서만 잡히는 것들이 있다. 단위 테스트가 통과해도 JPA 매핑이 스키마와 어긋나면 앱은 뜨지 않고,
`@Transactional`이 잘못 걸리면 실패해도 롤백되지 않는다.

### 실행 환경

Docker 금지 정책이 있으므로 Testcontainers를 쓰지 않는다. H2를 MySQL 호환 모드로 띄우고
**Flyway 마이그레이션을 실제로 실행**시킨다 — 그래야 마이그레이션과 엔터티가 맞는지도 같이 검증된다.

`src/test/resources/application.yml`은 DB 자동 설정을 **제외**하고 있다(단위 테스트용).
통합 테스트는 `integration` 프로파일로 그 제외를 되돌린다:

```java
@SpringBootTest
@ActiveProfiles("integration")
class DeliveryOrderServiceIntegrationTest { ... }
```

프로파일이 안 먹는 것 같으면(자동 설정 제외는 리스트 병합 때문에 프로파일 오버라이드가 헷갈릴 수 있다)
어노테이션에서 직접 비운다:

```java
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
```

`src/test/resources/application-integration.yml`이 없으면 만든다(한 번만).

### 무엇을 쓰나

- **트랜잭션 원자성** — `CLAUDE.md`가 하나로 묶으라고 한 전이(배차 확정, 배송 완료)에서
  중간에 예외가 나면 **전부 롤백되는지**. 이건 단위 테스트로는 절대 안 잡힌다.
- **DB 제약** — 유니크 제약, 생성 컬럼 기반의 "진행 중 1건" 제한이 실제로 두 번째 삽입을 막는지.
- **동시성** — 배차처럼 경쟁이 있는 곳. 두 스레드가 동시에 수락하면 **정확히 하나만** 성공하고
  나머지는 명확한 실패를 받아야 한다(부분 성공 없음).

  ```java
  @Test
  void 두_라이더가_동시에_수락하면_한_명만_배차된다() throws Exception {
      int 시도수 = 2;
      var 시작 = new CountDownLatch(1);
      var 완료 = new CountDownLatch(시도수);
      var 성공 = new AtomicInteger();

      try (var pool = Executors.newFixedThreadPool(시도수)) {
          for (long riderId : List.of(라이더A, 라이더B)) {
              pool.submit(() -> {
                  try {
                      시작.await();
                      matchingService.accept(orderId, riderId);
                      성공.incrementAndGet();
                  } catch (Exception ignored) {
                      // 경쟁 패배는 예외로 거부되는 것이 정상이다
                  } finally {
                      완료.countDown();
                  }
              });
          }
          시작.countDown();
          완료.await(5, TimeUnit.SECONDS);
      }

      assertThat(성공.get()).isEqualTo(1);
  }
  ```

  주의: 동시성 테스트에 `@Transactional`을 붙이면 안 된다. 테스트 트랜잭션 안에서는
  다른 스레드가 그 데이터를 볼 수 없어 경쟁 자체가 재현되지 않는다. 대신 테스트 끝에 직접 정리한다.
  H2에서 재현되지 않는 MySQL 고유 동작(갭 락 등)이 있으면 **그 한계를 단계 6 문서에 적는다.**

- **읽기 전용 경계** — `@Transactional(readOnly = true)` 메서드에서 변경이 반영되지 않는지 (필요할 때만).

## E2E 테스트

이슈의 「완료 조건」을 사용자 시나리오로 옮긴다. 계층을 하나씩 검증하는 게 아니라,
**바깥에서 들어가서 결과가 나오는 전 경로**를 한 번 통과시킨다.

### 백엔드 E2E (기본)

실제 HTTP로 요청한다. 필터·MDC·`ApiResponse` 래핑·상태 코드까지 전부 진짜로 지나간다.
`MockMvc`가 아니라 `webEnvironment = RANDOM_PORT` + `TestRestTemplate`을 쓰는 이유가 이것이다.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class DeliveryOrderE2ETest {

    @Autowired TestRestTemplate rest;

    @Test
    void 고객이_배송요청을_생성하고_조회하면_WAITING_상태로_보인다() {
        var 생성 = rest.postForEntity("/api/customer/deliveries", 요청, ApiResponse.class);
        assertThat(생성.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var 조회 = rest.getForEntity("/api/customer/deliveries/" + id, ApiResponse.class);
        assertThat(조회.getBody()).extracting("data.status").isEqualTo("WAITING");
    }
}
```

시나리오는 이슈의 「정상 흐름」과 「예외 흐름」을 그대로 따라간다. 예외 흐름도 최소 하나는 E2E로 덮는다
— 상태 코드와 에러 메시지 형식은 프론트가 의존하는 계약이라 여기서 깨지면 화면이 깨진다.

### 풀스택 E2E (브라우저)

화면까지 포함된 이슈에서, 브라우저 시나리오가 정말 필요한 경우에만.

이 저장소에는 **아직 프론트 테스트 러너가 없다.** Playwright 도입은 새 의존성이므로
`CLAUDE.md` 규칙상 **사람 확인 없이 설치하지 않는다.** 단계 2에서 확인받고, 도입했다면 그 결정을 단계 6에 남긴다.

확인을 받았다면 (최초 1회):

```bash
cd frontend
pnpm add -D @playwright/test
pnpm exec playwright install chromium    # PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD 환경이면 생략
```

시나리오는 `frontend/e2e/`에 두고, 백엔드(local 프로파일)와 `pnpm dev`를 함께 띄운 상태로 돌린다.

확인을 못 받았거나 브라우저까지 갈 필요가 없다면 **백엔드 E2E로 끝내고**,
프론트는 `pnpm typecheck` + 실제 화면에서 수동 확인으로 대신한다.
그 경우 "브라우저 E2E는 하지 않았다"를 단계 6 문서에 명시한다 — 안 한 것을 한 것처럼 두지 않는다.

## 실행과 보고

```bash
cd backend  && ./gradlew test
cd backend  && ./gradlew test --tests '*DeliveryOrder*'
cd frontend && pnpm typecheck
```

- **반드시 실행한다.** 실행하지 않은 테스트를 통과했다고 쓰지 않는다.
- 실패하면 실패 출력을 그대로 보여 주고 고친 뒤 다시 돌린다. 실패를 숨기지 않는다.
- 테스트가 잘못됐다는 확신 없이 단언을 느슨하게 바꿔서 통과시키지 않는다.
  구현이 틀렸을 가능성을 먼저 본다.
- 최종 보고에는 실제 결과(통과 개수, 실패 여부)를 적는다.
