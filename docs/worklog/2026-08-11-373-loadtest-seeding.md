# 부하테스트 시딩 스크립트 작업 기록

- 이슈: [#373](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/373)
- 브랜치: `feature/373-loadtest-seeding`
- 범위: backend (스크립트·인프라, 새 API 없음)
- 작성일: 2026-08-11

## 무엇을 만들었나

#259 부하테스트(SSE arm / Polling arm)가 setup()에서 호출할 k6 시딩 모듈을 만들었다.
고객·라이더 N쌍을 순수 HTTP API 호출로 만들어, 라이더 i가 주문 i만 수락하는 1:1 배정으로
`ASSIGNED` 상태 배송 N건을 준비한다. 추적 게이트(`authorizeTracking`)를 실제로 통과하는지
`GET /api/customer/deliveries/{id}/location`으로 검증했다(200 확인).

작업 도중 이 시딩과 무관하게 **Flyway가 이 저장소에서 기동 시 자동으로 돈 적이 없었다**는
훨씬 큰 문제를 발견해 같은 브랜치에서 먼저 고쳤다(아래 「스스로 판단한 것」).

### API

해당 없음 — 새 엔드포인트를 추가하지 않았다. 기존 API를 순서대로 호출하는 스크립트만 추가했다.

### 화면

해당 없음.

### 스키마 변경

- `V19__seed_default_fare_policy.sql` — 활성 요금 정책(`fare_policy`) 시드. `fare_policy`
  테이블(V8)은 스키마만 있고 행을 넣는 곳이 없어서, 새로 뜬 DB는 요금 견적(`/quote`)·배송요청
  생성이 전부 400으로 막혀 있었다. 값은 새로 정하지 않고 `CustomerDeliveryCreateE2ETest` 등
  기존 E2E 픽스처가 이미 쓰던 값(기본 3,000원 + 100m당 130원, 최대 30km)을 그대로 옮겼다.
  `WHERE NOT EXISTS` 조건부 INSERT라 이미 활성 정책이 있는 환경(예: 수동으로 넣어 둔 로컬 DB)
  에서는 아무것도 하지 않는다 — 안 그러면 `uk_fare_policy_active` UNIQUE 위반으로 Flyway 자체가
  실패해 앱이 안 뜬다.

## 사람이 고른 선택

### 1. 시딩 구현 경로

- **물었던 것**: 순수 k6 API 경유 / Java `@Profile` 시더(Spring 빈) / SQL 직접 INSERT + 로그인
  API 하이브리드 중 무엇으로 만들지.
- **선택지**:
  - (A) 순수 k6 API 경유 — 실제 도메인 제약(진행 중 1건 제한, ASSIGNED 전이, 포인트 402 등)을
    그대로 통과해야 하므로 시딩 자체가 계약 검증이 된다. 새 의존성·새 Java 코드가 없다. 단점은
    BCrypt·왕복 비용으로 N이 커지면 setup 시간이 길어진다.
  - (B) Java 시더(`@Profile("loadtest-seed")`) — 훨씬 빠르고 #446 이슈가 언급하는
    `AcceptLoadSeeder`와 방향이 같다. 단점은 운영 jar에 들어가는 코드가 늘고 Redis 세션을
    직접 만들어야 한다.
  - (C) SQL 직접 INSERT + 로그인 API 하이브리드 — 가장 빠르지만 k6는 MySQL 드라이버가 없어
    별도 사전 스크립트를 먼저 돌려야 하는 2단 구조가 된다.
- **고른 것**: (A)
- **근거**: 시딩이 곧 도메인 제약 통과 검증이 되는 것을 우선했다(사람 확인). 오늘 세션에서 SQL
  직접 INSERT로 수동 시딩했다가 `order_fare_snapshot`을 빠뜨려 배차 타임아웃 스캐너가
  `IllegalStateException`으로 계속 죽는 걸 직접 겪은 것이 이 판단의 배경이다.
- **영향**: N이 커지면(#259의 계단식 증가) setup 시간이 비례해 늘어난다. 실측 후 (B)로
  바꿀지는 별도 판단 — 이 이슈 범위 밖으로 남겨 둔다.

### 2. `fare_policy` 조달 방법

- **물었던 것**: 준비용 SQL 파일 / Flyway 마이그레이션 / 관리자 API 신설 중 무엇으로 조달할지.
- **선택지**:
  - (A) 별도 준비 SQL 파일 — Flyway·운영 경로를 안 건드리지만, 로컬 DB마다 수동 적용을 다시
    해야 하고 전제가 코드 밖에 남는다.
  - (B) Flyway 마이그레이션에 기본 정책 추가 — 모든 환경(로컬·CI·운영)이 같은 시작 상태를
    갖는다. 운영 데이터에도 적용되므로 요금 값을 팀이 합의해야 한다.
  - (C) 시딩 스크립트가 책임(관리자 API 신설) — 자기완결적이지만 #373 범위 밖 기능이 늘고
    인증·권한 설계가 따라온다.
- **고른 것**: (B)
- **근거**: 사람 확인. 값 자체는 새로 정한 게 아니라 기존 E2E 픽스처 값을 그대로 옮긴 것이라
  "팀이 합의해야 할 새 숫자"라기보다 "이미 쓰이던 값을 시드로 승격"에 가깝다.
- **영향**: 실제 영업 요금이 정해지면 이 행을 INACTIVE로 내리고 새 정책 버전을 추가하는 방식
  (행 수정이 아니라 새 행 + 상태 전환)으로 바꿔야 한다. 운영 DB에도 이 행이 그대로 들어간다
  — 실 요금 확정 전까지는 임시값이 노출된다는 뜻이므로, 배포 시점에 팀 공유가 필요하다.

### 3. 회차 간 격리

- **물었던 것**: RUN_ID 접미사 + 정리 SQL 제공 / k6 teardown으로 자동 삭제 중 무엇으로 할지.
- **선택지**:
  - (A) RUN_ID 접미사 + 정리 SQL — 충돌 자체를 없앤다(같은 회차를 두 번 돌리지 않는 한 정리를
    안 해도 다음 실행이 막히지 않는다). 삭제용 API가 없어 SQL을 직접 실행해야 한다.
  - (B) teardown 자동 삭제 — DB가 깨끗하게 유지되지만, 부하테스트 중간에 k6가 죽거나
    Ctrl+C로 중단되면 teardown이 안 돌아 다음 회차가 막힌다.
- **고른 것**: (A)
- **근거**: 사람 확인. 부하테스트는 실패로 끝나는 경우가 실험 과정의 일부라, teardown 의존은
  깨지기 쉽다고 판단.
- **영향**: `cleanup-seed.sql`을 따로 만들어야 했다(아래 참고). 실행을 안 하면 `lt_*` 계정이
  DB에 계속 쌓인다 — 재실행 자체는 막히지 않지만(RUN_ID가 다르면 unique 충돌이 안 남) 청소
  안 된 상태가 오래 가면 DB가 지저분해진다.

## 스스로 판단한 것

- **Flyway가 기동 시 한 번도 자동 실행되지 않고 있었다는 것을 build.gradle 수정으로 이 브랜치에서
  바로 고쳤다** — 근거: `spring-boot-autoconfigure-4.1.0.jar`에 Flyway 클래스가 0개였다(직접
  `jar tf`로 확인). Spring Boot 4.0이 거대한 `spring-boot-autoconfigure`를 기능별 모듈로
  쪼개면서 `FlywayAutoConfiguration`이 `spring-boot-flyway` 모듈로 빠졌고, 그 모듈은
  `spring-boot-starter-flyway`를 명시적으로 추가해야 딸려 온다(Boot 3.x까지는 `flyway-core`만
  있어도 자동 등록됐다). `CLAUDE.md`는 "Spring Boot 3.4.x"를 정본으로 적고 있지만 실제
  `build.gradle`은 4.1.0이었다 — 이 저장소가 그 사이 Boot 4로 올라갔는데 Flyway 모듈화를
  아무도 못 잡은 것으로 보인다. `spring-boot-starter-flyway`를 추가한 뒤 임시 인스턴스로
  V19가 실제로 적용되는 것과(`flyway_schema_history`에 19 기록, `fare_policy` 1행 생성),
  전체 테스트 643개가 여전히 통과하는 것을 확인했다(사람 확인, "지금 브랜치에서 먼저 고치고
  진행"으로 결정).
  - **왜 지금까지 아무도 몰랐나**: 로컬 Docker MySQL 볼륨(`turkey-mysql-data`)에 V1~V18
    스키마가 이전(Boot 3.4 시절) 실행으로 이미 심어져 있어서 `ddl-auto: validate`가 조용히
    통과했다. 새 컬럼 없이 새 데이터만 추가하는 V19가 처음으로 이 공백을 드러냈다.
- **`agreedTermIds: []`로 가입을 통과시켰다** — 근거: 로컬 DB의 `term` 테이블이 0행이라, 활성
  약관이 없으면 필수 약관 검증이 빈 리스트를 그대로 통과시킨다(`RiderSignupService.resolveAgreedTerms`
  로직 확인). 약관 조회 API(`GET /api/terms`류)가 아직 없어 다른 선택지가 없었다.
- **`debugCode`(local 프로파일 전용 응답 필드)에 의존했다** — 근거: `PhoneVerificationResponse.from`이
  `includeDebugCode`가 true일 때만(로컬 프로파일 한정) 인증번호를 응답에 실어 준다. 이 스크립트는
  local 프로파일이 아닌 서버를 겨냥하면 `debugCode`가 비어 있어 명시적으로 에러를 던지고 멈추게
  했다 — 조용히 무한 대기하거나 이상한 값으로 진행하는 대신 즉시 실패시키는 편이 부하테스트
  스크립트에서는 더 안전하다고 판단했다.
- **좌표를 다양화하지 않고 고정 좌표(강남→송파) 하나만 썼다** — 근거: #259의 종속변인(지연·용량)이
  좌표가 아니라 동시 연결 수 N에 달려 있어서, 좌표를 다양화해도 실험 결과에 영향이 없다.

## 일부러 하지 않은 것

- **SSE/Polling arm 본 스크립트 자체는 만들지 않았다** — #373은 "시딩"만 범위이고, arm
  스크립트는 #259 §5의 별도 채워야 할 항목이다. `seed.js`는 그 arm 스크립트가 `import`해 쓰는
  모듈로만 존재한다.
- **N이 큰 경우(수백 단위)의 setup 성능은 실측하지 않았다** — 이슈 상세요구사항이 "실측 후
  판단"이라고 명시한 부분이라, #259 본 실험에서 N을 늘려 가며 확인해야 한다. 검증은 N=2로만
  했다.
- **약관 조회 API 연동은 하지 않았다** — `term` 테이블이 0행인 현재 상태에 맞춘 것이고, 약관
  시드가 생기면(`GET /api/terms`류 API 신설 시) `agreedTermIds: []`를 실제 조회 결과로
  바꿔야 한다.
- **배포(EC2) 환경의 Flyway 상태는 확인하지 않았다** — 로컬에서 재현한 문제와 같은 증상이
  배포 환경에도 있을 수 있다(운영 DB도 기동 시 자동 마이그레이션에 의존한다). 사람 확인:
  이번 보고에만 담고 별도 이슈는 나중에 올리기로 함.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 수동 검증 (E2E 성격) | `backend/loadtest/seed.js` (직접 실행) | N=2로 `seedPairs` 호출 → 28건 HTTP 요청 전부 성공, `delivery_order` 2건이 `ASSIGNED`로 라이더 1:1 배정, `GET /location`(추적 게이트)이 200 반환 |
| 회귀 | 전체 백엔드 테스트 스위트 | Flyway 의존성 교체 + V19 추가가 기존 동작을 안 깨는지 |

실행 결과:

```text
./gradlew test --tests "*CustomerDeliveryCreateE2ETest*" --tests "*FarePolicy*" --tests "*DeliveryService*"
  → BUILD SUCCESSFUL (8개 테스트 클래스 파일 생성 확인)

./gradlew test (전체)
  → BUILD SUCCESSFUL in 8m 32s, 총 643개 테스트, 실패 0, 에러 0, 스킵 0

k6 run seed.js 검증(임시 인스턴스, N=2):
  http_req_failed: 0.00% (0/28)
  DB 확인: order_id=4,5 모두 ASSIGNED, customer/rider 1:1 매칭
  GET /api/customer/deliveries/4/location → 200 (추적 게이트 통과 확인)
  cleanup-seed.sql 실행 → member 4명 삭제, 잔여 0건 확인
```

### 검증하지 못한 것

- N이 큰 경우(예: #259가 다룰 50~수백 단위)의 setup 소요 시간·안정성은 재지 않았다.
- 배포(EC2) 환경에서 Flyway 자동 적용이 실제로 되는지는 확인하지 않았다 — 로컬 재현만 했다.
- k6 arm 스크립트(SSE/Polling)에서 `seed.js`를 실제로 import해 쓰는 통합 시나리오는 아직 없다
  (arm 스크립트 자체가 미작성이라 이 시딩 모듈 단독으로만 검증했다).

## 새로 생긴 미결 사항

- **배포 환경(EC2)의 Flyway 자동 적용 여부를 확인해야 한다.** `spring-boot-starter-flyway`
  누락이 로컬에서 재현됐고, 운영 배포도 같은 `build.gradle`을 쓰므로 최근 배포부터는 신규
  마이그레이션이 자동 적용되지 않았을 가능성이 있다. 사람 확인: 이번 작업 기록에만 남기고
  별도 이슈는 추후 등록하기로 함 — `CLAUDE.md` 갱신 시 이 항목도 반영 필요.
- **`docs/05-local-dev.md`의 "V1~V17 정상 적용 확인"(Spring Boot 3.4.1 전제) 문구가 지금
  기준으로 낡았다.** Boot 4.1.0으로 이미 올라간 상태와 어긋난다 — 별도로 갱신 필요.
- N이 큰 경우 (A) 순수 API 경유 시딩의 setup 시간이 어느 선에서 부담스러워지는지 실측이
  필요하다. 부담스러우면 #446이 언급하는 Java `@Profile` 시더(`loadtest-seed`)로 갈아탈지
  판단해야 한다.
- 약관(`term`) 시드가 로컬 DB에 생기면 `seed.js`의 `agreedTermIds: []`를 실제 조회 API 연동으로
  바꿔야 한다(약관 조회 API 자체도 아직 없음, 기존 미결 항목과 동일).
