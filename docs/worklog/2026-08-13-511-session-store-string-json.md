# 세션 Redis 저장 구조 Hash → String(JSON) 변경 작업 기록

- 이슈: [#511](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/511)
- 브랜치: `feature/511-session-store-string-json`
- 범위: domain (Redis 저장소 내부 구조 변경, API 계약 변경 없음)
- 작성일: 2026-08-13

## 무엇을 만들었나

`RedisSessionStore.create()`가 `HSET` + `EXPIRE`(별도 트랜잭션 아닌 별도 명령 2회) 로 값과 TTL을 나눠
걸던 것을, JSON `{"memberId": ...}` 값을 `SET ... EX` 한 번으로 쓰도록 바꿨다. `findMemberId()`도
`opsForHash().get()` → `opsForValue().get()` + Jackson 파싱으로 맞췄다. `extend()`/`delete()`는
자료구조와 무관해 변경 없음(이슈에 명시된 대로).

### API

해당 없음 — 세션 쿠키·인증 흐름·응답 스키마 변화 없음.

### 화면

해당 없음.

### 스키마 변경

해당 없음 — Redis 키 자료구조 변경이라 Flyway 대상 아님.

## 사람이 고른 선택

### 1. WRONGTYPE 마이그레이션 방어 로직 포함 여부

- **물었던 것**: 배포 직후 구버전(Hash 타입) 세션 키가 남아 있는 짧은 창(최대 2시간, 세션 TTL)에
  새 코드가 `GET`을 쓰면 Redis가 WRONGTYPE을 던진다. 이걸 잡아 `Optional.empty()`로 흡수할지,
  그대로 500으로 새게 둘지.
- **선택지**:
  - (A) 방어 로직 추가 — `findMemberId()`가 `RedisSystemException`을 잡아 없음으로 처리 → 기존
    로그인 사용자는 배포 직후 첫 요청에서 401(재로그인 유도)을 받는다 / 단점: 코드 몇 줄 추가
  - (B) 방어 없음 — 코드는 더 단순하지만 배포 순간 기존 세션 보유자가 원인 불명의 500을 받는다
- **고른 것**: (A)
- **근거**: 사용자가 AskUserQuestion에서 "방어 로직 추가(권장)"를 선택.
- **영향**: `RedisSessionStore.findMemberId()`가 `org.springframework.data.redis.RedisSystemException`을
  잡아 빈 결과로 흡수한다. WRONGTYPE 외의 다른 `RedisSystemException`(예: 연결 문제)도 같은 경로로
  삼켜져 401이 되는데, 이 이슈 범위에서는 구버전 키 마이그레이션 창만 노려 추가한 것이라 그 외
  Redis 장애 처리 정책은 "이 이슈 범위 밖"(이슈 본문 명시)으로 손대지 않았다.

### 2. `role` 파라미터 유지 여부

- **물었던 것**: 이슈 본문은 `create(sessionId, memberId, role, ttl)`에서 `role`을 유지할지 제거할지
  결정을 요청했다.
- **실제로는 이미 해결돼 있었다**: `SessionStore` 인터페이스를 확인해 보니 2026-08-12 정리(`CLAUDE.md`
  「확정된 결정」)에서 `role`·`expiresAt` 필드가 이미 인터페이스에서 제거된 상태였다(읽는 코드가
  0곳이라 없앰). 이슈가 작성된 시점과 실제 코드 상태 사이에 간극이 있었던 것으로 보인다.
- **고른 것**: 해당 없음(이미 결정돼 있어 사람에게 다시 묻지 않았다).
- **근거**: 코드를 먼저 확인해 이미 해소된 질문임을 확인.
- **영향**: 없음.

## 스스로 판단한 것

- **JSON 직렬화에 Jackson 3(`tools.jackson.databind.ObjectMapper`) 사용**: 이슈가 제안한 대로 이미
  있는 Spring 관리 `ObjectMapper` 빈을 그대로 썼다(새 의존성 없음). 이 저장소가 Spring Boot 4.1이라
  Jackson 3로 이미 올라가 있고(`TrackingPublisher`가 같은 패키지를 쓰는 선례), `com.fasterxml.jackson`
  이 아니라 `tools.jackson.databind`를 임포트해야 한다 — 혼동하기 쉬운 지점이라 명시해 둔다.
- **값 타입을 `private record SessionValue(Long memberId)`로 정의**: `Map`을 직접 쓰는 대신 작은
  레코드를 둔 이유는, 이슈가 "필드가 늘어도 구조 변경 없이 확장 가능"을 목표로 명시했고 레코드에
  필드를 추가하는 편이 `Map` 키 문자열을 흩뿌리는 것보다 다음 사람이 안전하게 확장하기 쉽다.
- **`RedisSystemException`을 catch 대상으로 선택**: WRONGTYPE은 Lettuce 드라이버 예외를 Spring Data
  Redis가 `RedisSystemException`(DataAccessException 계열)으로 감싸 던진다. 이 예외를 좁혀 잡았다 —
  더 넓게 `RuntimeException`을 잡으면 JSON 파싱 실패 같은 다른 원인도 같이 삼켜 디버깅이 어려워진다.
- **테스트 위치**: 이슈가 요구한 "RedisSessionStore 전용 테스트"는 실제 Redis에 붙는 통합 테스트로만
  추가했다(`RedisSessionStoreIntegrationTest`에 2개 케이스: 단일 SET 저장 확인, 레거시 Hash 키 방어).
  `testing.md`가 "저장 형태 검증은 실제 Redis에서"라고 명시하고 있어, 목(mock) 기반 순수 단위 테스트는
  같은 것을 이중으로 확인하는 셈이라 추가하지 않았다.

## 일부러 하지 않은 것

- **Redis 연결 자체가 끊긴 경우의 처리 방식 변경**: 이슈 본문이 명시적으로 범위 밖이라고 선을 그음 —
  손대지 않았다.
- **`RedisSystemException`을 더 세분화해 WRONGTYPE만 골라 잡는 것**: 예외 메시지 파싱으로 WRONGTYPE만
  구분할 수도 있었지만, 배포 마이그레이션 창이라는 좁은 목적에는 타입 단위로 잡는 것으로 충분하다고
  판단(과설계 회피).

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 통합 | `RedisSessionStoreIntegrationTest` | 세션 생성이 String 타입 키에 JSON+TTL을 한 번에 거는지, 레거시 Hash 키가 WRONGTYPE 대신 빈 결과로 처리되는지, 기존 슬라이딩 TTL 케이스 |
| E2E | `RiderLoginE2ETest`, `CustomerLoginE2ETest` | 로그인 성공 시 실제 Redis에 새 JSON 형식으로 세션이 저장되는지 |

실행 결과:

```text
./gradlew test --tests 'com.turkey.quick.common.auth.RedisSessionStoreIntegrationTest' \
               --tests 'com.turkey.quick.rider.controller.RiderLoginE2ETest' \
               --tests 'com.turkey.quick.customer.controller.CustomerLoginE2ETest'
→ RedisSessionStoreIntegrationTest 4 tests, 0 failures
→ RiderLoginE2ETest 3 tests, 0 failures
→ CustomerLoginE2ETest 5 tests, 0 failures

./gradlew test (전체 회귀) → 665 tests, 0 failures, 0 errors
```

### 검증하지 못한 것

- 실제 배포 환경에서 구버전 Hash 세션 키가 실제로 발생하는 마이그레이션 창 자체는 재현하지 않았다
  (로컬에서 Hash 키를 수동으로 만들어 흉내낸 것이라, 배포 스크립트의 무중단 배포 타이밍까지는
  검증 범위 밖).

## 새로 생긴 미결 사항

- 없음.
