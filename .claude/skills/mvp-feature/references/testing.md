# 테스트 규칙 — 단위 · 통합 · E2E

단계 5에서 읽는다.

세 층을 나누는 이유는 형식을 채우기 위해서가 아니라, **각 층이 잡을 수 있는 버그가 다르기 때문**이다.
같은 것을 세 번 검증하면 시간만 쓰고 아무것도 더 잡지 못한다. 층마다 다음을 맡는다.

| 층 | 잡는 것 | 스프링 컨텍스트 | DB |
|---|---|---|---|
| 단위 | 도메인 규칙, 상태 전이 거부, 계산식 | 없음 | 없음 |
| 통합 | 트랜잭션 경계, JPA 매핑, DB 제약, 동시성 | 있음 | Docker MySQL 8.4 |
| E2E | 이슈의 「완료 조건」이 사용자 관점에서 성립하는가 | 있음 | Docker MySQL 8.4 |

## 공통 관례

- JUnit 5 + AssertJ. `assertThat`, `assertThatThrownBy`.
- 테스트 이름은 **영문 camelCase 메서드명 + 한글 `@DisplayName`**으로 쓴다. 메서드명은 코드로 읽히고,
  검증 내용은 실행 리포트에 문장으로 남는다. 케이스가 여러 갈래면 `@Nested`로 묶고 묶음에도
  `@DisplayName`을 붙인다.
  ```java
  @Test
  @DisplayName("진행 중 배송요청이 있으면 새 요청을 거부한다")
  void shouldRejectNewOrderWhenOngoingOrderExists() { ... }
  ```
  초기 테스트는 한글 스네이크 케이스였고 아직 일부 남아 있다(2026-08-10 기준 151개, camelCase 466개).
  **일괄 개명하지 않는다** — 기능과 무관한 대량 변경이라 `CLAUDE.md` 작업 원칙에 어긋난다. 이미 있는
  파일에 케이스를 덧붙일 때는 그 파일의 기존 스타일에 맞춘다(한 파일 안에서 두 스타일이 섞이는 게 더 나쁘다).
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

본보기: `order/service/DeliveryServiceTest`(거리 계산을 실측값과 대조), `order/domain/FarePolicyTest`.
단언에 `.as("...")`로 무엇이 성립해야 하는지 붙이면 실패 출력만 보고도 원인을 안다.

## 통합 테스트

여기서만 잡히는 것들이 있다. 단위 테스트가 통과해도 JPA 매핑이 스키마와 어긋나면 앱은 뜨지 않고,
`@Transactional`이 잘못 걸리면 실패해도 롤백되지 않는다.

### 실행 환경 — 로컬 Docker MySQL

**H2를 쓰지 않는다.** 로컬 개발과 똑같이 `backend/docker-compose.yml`의 **MySQL 8.4 컨테이너**에 붙는다.
이유는 `docs/05-local-dev.md`에 적힌 것과 같다 — 호환 모드는 MySQL이 아니다. `TINYINT(1)`을
Connector/J가 `BIT`으로 보고해 `ddl-auto: validate`가 깨진 사고가 실제로 있었고, H2에서는 재현되지
않아 배포 후에야 드러났다. **테스트가 통과해도 배포에서 깨지면 그 테스트는 값을 못 한 것이다.**

Testcontainers는 **금지가 아니다**(`CLAUDE.md` 금지 사항이 2026-07-29에 "Docker 사용 제약 없음"으로
바뀌었다). 다만 지금 방식은 테스트가 컨테이너를 띄우는 게 아니라 **개발자가 이미 띄워 둔 로컬 개발
컨테이너에 붙는 것**이고, 기존 테스트가 전부 그 전제로 쓰여 있다. 바꾸려면 별도 이슈로 논의한다.

테스트 전에 컨테이너가 `healthy`여야 한다:

```bash
cd backend
docker compose up -d
docker compose ps        # STATUS 가 healthy 인지 확인(초기 기동 20~30초)
```

`src/test/resources/application.yml`은 DB 자동 설정을 **제외**하고 있다(단위 테스트용).
DB가 필요한 테스트는 `integration` 프로파일로 그 제외를 되돌리고, MySQL 접속 정보를 받는다.
자동 설정 제외는 리스트 병합 때문에 프로파일만으로는 덜 풀리므로, **어노테이션에서 직접 비운다**
(저장소의 기존 테스트가 전부 이 형태다):

```java
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
class DeliveryOrderServiceIntegrationTest extends IntegrationTestSupport { ... }
```

Flyway는 이 프로파일에서도 실제로 돌아간다. 마이그레이션과 엔터티 매핑이 어긋나면 컨텍스트가 아예
뜨지 않으므로, 그 검증이 통합 테스트에 공짜로 딸려 온다.

### 테스트 데이터 정리 — `IntegrationTestSupport`를 상속한다

인메모리 DB와 달리 **MySQL은 테스트가 끝나도 데이터가 남는다.** 앞 테스트가 만든 회원이 다음 테스트로
새어 들어가고, 두 번째 실행부터는 `login_id`·`phone_number` unique 제약에 걸려 실패한다.

그래서 DB를 쓰는 테스트는 `com.turkey.quick.support.IntegrationTestSupport`를 상속한다.
`@BeforeEach`로 `DatabaseCleaner`가 모든 테이블을 TRUNCATE 한다(`flyway_schema_history`는 제외 —
지우면 다음 기동에서 스키마를 처음부터 다시 적용하려 든다). E2E도 같은 어노테이션 조합에
`webEnvironment = RANDOM_PORT`만 더한 형태다(아래 「백엔드 E2E」).

- **`@Transactional` 롤백으로 대신할 수 없다.** E2E는 HTTP로 다른 스레드·커넥션에서 커밋되므로
  테스트 트랜잭션이 그 데이터를 되돌리지 못한다. 동시성 테스트도 마찬가지다(아래 참고).
- 테이블 목록은 `information_schema`에서 매번 읽으므로, 새 마이그레이션으로 테이블이 늘어도
  `DatabaseCleaner`를 고칠 필요가 없다.
- **주의: 개발용과 같은 `turkey` 스키마를 공유한다**(사람 확인, 2026-07-29). 즉 `./gradlew test`를
  돌리면 **로컬에서 손으로 넣어 둔 개발 데이터가 지워진다.** 남겨야 할 로컬 데이터가 있으면 먼저 백업한다.

### Redis도 컨테이너에 붙는다 (2026-07-29 변경)

**통합·E2E는 실제 Redis를 쓴다.** 예전에는 인메모리 대체(`InMemorySessionStore` 등)를 `@TestConfiguration`
+ `@Primary`로 끼웠는데, 그러면 대체 구현의 자료구조만 확인하는 셈이어서 TTL 만료·자료구조 동작·저장
형태를 하나도 보장하지 못했다. MySQL을 H2에서 옮긴 것과 같은 이유다.

- **단위 테스트는 여전히 인메모리 대체를 쓴다.** `new InMemorySessionStore()`처럼 직접 만들어 넣는다.
  단위 테스트가 컨테이너를 요구하면 위 층 구분표(단위: 스프링 없음, DB 없음)가 무너진다.
- **E2E는 `@TestConfiguration`으로 Redis 저장소를 덮지 않는다.** `SessionStore`·`VerificationCodeStore`·
  `RiderLocationRepository`를 그냥 주입받아 쓰고, 저장 형태를 확인해야 하면 `StringRedisTemplate`으로
  실제 키를 읽는다(그게 실제 Redis로 바꿔서 얻는 이득이다).
- 외부 벤더 대체(`FakeSmsSender`)는 그대로 `@Primary`로 끼운다. 이건 Redis와 무관하다.
- **테스트는 개발용과 다른 로직 DB(`database: 1`)를 쓴다.** MySQL은 개발 스키마를 공유해 테스트가
  개발 데이터를 지우지만, Redis 세션이 지워지면 브라우저에서 매번 다시 로그인해야 해서 비용이 크다.
  같은 엔진이므로 검증 충실도는 동일하다.
- 정리는 `IntegrationTestSupport`가 `RedisCleaner`로 `FLUSHDB`까지 수행한다. 실제 Redis는 값이
  프로세스보다 오래 살아 **앞 테스트의 세션·인증번호·위치가 다음 테스트로 샌다.** 특히
  `DatabaseCleaner`의 TRUNCATE가 AUTO_INCREMENT를 1로 리셋해 모든 테스트의 첫 회원이 같은
  `member_id`를 받으므로, member id로 키를 만드는 저장소는 반드시 오염된다(#82에서 실제로 물렸다).

> ⚠️ **개발 PC에 Redis를 직접 설치하지 않는다.** 호스트 Redis는 `127.0.0.1:6379`에, 컨테이너는
> `*:6379`에 바인딩하는데 **더 구체적인 호스트 쪽이 이긴다.** 그러면 `localhost`로 붙는 애플리케이션과
> 테스트가 컨테이너가 아니라 호스트 인스턴스에 **조용히** 연결된다 — 2026-07-29에 실제로 그 상태로
> 테스트가 돌고 있었다(호스트 8.8.0 vs 컨테이너 7.4). 설정을 다시 읽는 것으로는 알 수 없어서,
> `RedisCleaner`가 연결된 인스턴스의 `redis_version`을 확인해 이 상황을 실패로 만든다.
> `lsof -nP -iTCP:6379 -sTCP:LISTEN`으로 누가 잡고 있는지 볼 수 있다.

### 무엇을 쓰나

- **트랜잭션 원자성** — `CLAUDE.md`가 하나로 묶으라고 한 전이(배차 확정, 배송 완료)에서
  중간에 예외가 나면 **전부 롤백되는지**. 이건 단위 테스트로는 절대 안 잡힌다.
- **DB 제약** — 유니크 제약, 생성 컬럼 기반의 "진행 중 1건" 제한이 실제로 두 번째 삽입을 막는지.
- **동시성** — 배차처럼 경쟁이 있는 곳. 두 스레드가 동시에 수락하면 **정확히 하나만** 성공하고
  나머지는 명확한 실패를 받아야 한다(부분 성공 없음).

  형태는 `CountDownLatch` 두 개(출발 신호 + 완료 대기)로 스레드를 같은 순간에 풀고, 성공 횟수를
  `AtomicInteger`로 세어 **정확히 1**인지 본다. 경쟁에서 진 쪽이 예외로 거부되는 것은 정상이므로
  삼킨다. 기존 예: `payment/service/CustomerPaymentServiceIntegrationTest`,
  `order/service/DeliveryCancelIntegrationTest`.

  **주의: 동시성 테스트에 `@Transactional`을 붙이면 안 된다.** 테스트 트랜잭션 안에서는
  다른 스레드가 그 데이터를 볼 수 없어 경쟁 자체가 재현되지 않는다. 정리는 `IntegrationTestSupport`가
  하므로 직접 지울 필요는 없다.

  DB가 실제 MySQL/InnoDB이므로 **갭 락, `SELECT ... FOR UPDATE`, 유니크 인덱스 경쟁이 배포와 같은
  방식으로 재현된다.** 배차 동시성은 조건부 UPDATE(CAS)로 확정돼 있으므로(ADR-006, `CLAUDE.md`
  「확정된 결정」) 방식을 다시 고르지 말고, 그 구현이 실제로 하나만 성공시키는지를 여기서 실증한다.

- **읽기 전용 경계** — `@Transactional(readOnly = true)` 메서드에서 변경이 반영되지 않는지 (필요할 때만).

## E2E 테스트

이슈의 「완료 조건」을 사용자 시나리오로 옮긴다. 계층을 하나씩 검증하는 게 아니라,
**바깥에서 들어가서 결과가 나오는 전 경로**를 한 번 통과시킨다.

### 백엔드 E2E (기본)

실제 HTTP로 요청한다. 필터·MDC·`ApiResponse` 래핑·상태 코드까지 전부 진짜로 지나간다.
`MockMvc`가 아니라 `webEnvironment = RANDOM_PORT` + `TestRestTemplate`을 쓰는 이유가 이것이다.

**`customer/controller/CustomerSessionE2ETest`를 복사해서 시작한다** — 저장소의 기존 E2E가 전부 같은
형태다. 클래스 선언 세 줄이 뼈대의 전부이고, 그중 두 가지를 빠뜨리면 각각 컨텍스트가 뜨지 않거나
두 번째 실행부터 unique 제약으로 깨진다:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.autoconfigure.exclude=")   // ← DB 자동설정 되살리기
@ActiveProfiles("integration")
class DeliveryOrderE2ETest extends IntegrationTestSupport { ... }   // ← 테이블·Redis 정리
```

나머지는 `TestRestTemplate`을 주입받아 실제 HTTP로 부르는 것뿐이다. 픽스처는 매 테스트가 직접 만든다
(앞 테스트 데이터는 이미 비워져 있다). Redis 저장소는 덮지 않고, 외부 벤더 대체(`SmsSender`)가
필요하면 그것만 `@TestConfiguration` + `@Primary`로 끼운다.

쿠키를 직접 실어 보내는 것도 형식이 아니다. 인증은 인터셉터의 `addPathPatterns` 등록에 달려 있어
**등록을 빠뜨리면 그 API는 인증 없이 열린다**(`CLAUDE.md` 「확정된 결정」). 쿠키 없이 호출해 401이
나오는지도 한 케이스로 덮으면 그 누락이 여기서 잡힌다.

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

DB 컨테이너가 떠 있어야 통합·E2E가 돈다. 순서가 곧 체크리스트다.

```bash
cd backend
docker compose up -d                          # MySQL 8.4 (+ Redis). 이미 떠 있으면 그대로 둔다
docker compose ps                             # healthy 확인 후 진행

./gradlew test                                # 전체
./gradlew test --tests '*DeliveryOrder*'      # 이번 이슈 범위만

cd ../frontend && pnpm typecheck
```

자주 만나는 실패 두 가지는 테스트 코드 문제가 아니다:

- `Communications link failure` / 접속 거부 — 컨테이너가 없거나 아직 초기화 중이다. `docker compose ps`로
  `healthy`를 확인하고 다시 돌린다.
- `Schema-validation: wrong column type` / Flyway `checksum mismatch` — 마이그레이션과 엔터티가
  어긋났거나 이미 적용된 마이그레이션 파일을 수정했다. 대응은 `docs/05-local-dev.md`를 따른다
  (급하면 `docker compose down -v && docker compose up -d`로 스키마 전체 재적용).

- **반드시 실행한다.** 실행하지 않은 테스트를 통과했다고 쓰지 않는다.
- 실패하면 실패 출력을 그대로 보여 주고 고친 뒤 다시 돌린다. 실패를 숨기지 않는다.
- 테스트가 잘못됐다는 확신 없이 단언을 느슨하게 바꿔서 통과시키지 않는다.
  구현이 틀렸을 가능성을 먼저 본다.
- 최종 보고에는 실제 결과(통과 개수, 실패 여부)를 적는다.
