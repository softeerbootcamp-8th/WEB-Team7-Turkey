# 인증번호 요청 작업 기록

- 이슈: [#20](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/20)
- 브랜치: `feature/20-phone-verification-request`
- 범위: backend
- 작성일: 2026-07-28

## 무엇을 만들었나

회원가입/계정 찾기용 휴대전화 인증번호를 발급·발송하는 API를 만들었다. 대상 도메인(`member`)에
리포지토리·서비스·컨트롤러가 이번에 처음 생겼고, 이 과정에서 프로젝트 전역 예외 처리 골격과
Redis 의존성 자체도 이번에 처음 배선했다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| POST | `/api/phone-verifications` | 인증번호 발급·발송 | 400 형식 오류, 409 이미 가입된 번호(SIGNUP), 429 재전송 쿨다운, 502 발송 실패 |

### 화면

해당 없음 (백엔드 API만, 이슈에 화면 요구 없음).

### 스키마 변경

해당 없음. 인증번호는 Redis TTL로만 관리하고 MySQL 마이그레이션은 추가하지 않았다.

## 사람이 고른 선택

### 1. 인증번호를 어디에 저장할 것인가 (Redis 용도 확장)

- **물었던 것**: `CLAUDE.md`의 "Redis 용도는 3가지로 한정(세션/라이더 위치/GEO)" 정책과
  인증번호 저장이 충돌함. 4번째 용도로 확장할지, MySQL에 만료시각 컬럼으로 둘지.
- **선택지**:
  - (A) Redis 용도를 4번째로 확장 — TTL이 자연스러움 / 확정된 정책을 건드림
  - (B) MySQL에 만료시각 컬럼 — 정책 유지 / 만료 처리를 애플리케이션이 직접 폴링해야 함
- **고른 것**: (A)
- **근거**: 사용자가 "Redis 용도를 4번째로 확장"을 선택.
- **영향**: `CLAUDE.md`·`docs/03-erd.md`의 Redis 데이터 모델을 갱신했다. `spring-boot-starter-data-redis`,
  `application.yml`의 `spring.data.redis`, `docker-compose.yml`의 `redis` 서비스를 새로 추가했다.

### 2. 인증번호 로깅 금지 규칙과의 충돌

- **물었던 것**: MVP 모의 발송에서 인증번호 확인 편의를 위해 로그에 남기자는 제안이
  `docs/logging-guidelines.md` §8("인증번호는 로그에 남기지 않는다")과 충돌함.
- **선택지**:
  - (A) 서버 로그에만 남김 — 편함 / 규칙 위반
  - (B) local 프로파일에서만 응답 body에 코드 포함 — 규칙 준수, 로그가 아니라 응답이라 안전 / 프로파일 분기 로직 필요
  - (C) 그래도 로그에 남김(규칙 예외로 문서화) — 가장 편함 / 정책 훼손
- **고른 것**: (B)
- **근거**: "local 프로파일에서만 응답에 코드 포함하는 게 테스트 편의성을 위해 좋을 것 같다"
- **영향**: `PhoneVerificationController`가 `Environment#matchesProfiles("local")`로 분기해
  `PhoneVerificationResponse.debugCode`를 채운다. 로그(`LoggingSmsSender`)에는 마스킹된 전화번호만 남기고
  인증번호는 어떤 프로파일에서도 로그에 남기지 않는다.

### 3. 인증번호 규격값

- **물었던 것**: 자릿수·유효시간·재전송 쿨다운이 이슈 본문에 없음.
- **고른 것**: 6자리 숫자 / 5분 만료 / 60초 재전송 쿨다운 (모두 추천안 그대로 선택)
- **근거**: 국내 SMS 인증 관행값을 그대로 채택.
- **영향**: `PhoneVerificationService`의 `CODE_LENGTH`/`CODE_TTL`/`RESEND_COOLDOWN` 상수. 이후 #21(인증번호 확인)이
  같은 Redis 키(`phone-verification:code:{purpose}:{phoneNumber}`)를 읽어야 하므로 키 포맷을 바꾸려면 #21과 함께 조정해야 한다.

## 스스로 판단한 것

- **패키지 위치**: `member` 아래에 뒀다 — 인증번호는 회원가입/계정찾기(`member` 도메인 관심사)와 강하게 결합돼
  있고, `common/auth`는 쿠키 세션(로그인 이후) 전용으로 이미 성격이 다르게 정해져 있어서(`backend.md` 「인증」절).
- **VerificationCodeStore를 인터페이스로 분리한 이유**: Redis 구현체(`RedisVerificationCodeStore`)와
  테스트용 인메모리 구현체(`InMemoryVerificationCodeStore`)를 나눠, MySQL 통합 테스트를 H2로 대체하는 것과
  같은 방식으로 로컬 Redis 없이 통합·E2E 테스트가 돌게 했다. Testcontainers/embedded-redis 같은 새
  라이브러리를 추가하지 않기 위한 선택이기도 하다.
- **BusinessException을 단일 클래스로**: 409/429/502처럼 400과 구분해야 하는 케이스가 이번 이슈에서
  여럿 나왔지만, 각각 별도 예외 클래스를 만들지 않고 `HttpStatus`를 들고 다니는 예외 하나로 처리했다.
  `backend.md`가 "이슈 범위를 넘는 대규모 예외 체계를 설계하지 말라"고 명시해서, 케이스가 늘어나면 그때
  세분화하기로 했다.
- **QuickApplicationTests 수정**: 이번이 이 저장소의 첫 JPA 리포지토리(`MemberRepository`)라서, DB 자동설정을
  제외한 기존 단위테스트용 컨텍스트로는 `PhoneVerificationService`(리포지토리 의존)가 뜨지 못해
  `contextLoads()`가 깨졌다. `integration` 프로파일(H2)로 바꿔 고쳤다. 이후 리포지토리가 계속 추가돼도
  이 테스트는 그대로 통과한다.
- **전화번호 정규화**: 요청의 하이픈 포함 여부와 `member.phone_number`(하이픈 없이 저장 가정)가 어긋나면
  중복 확인이 무의미해지므로, 서비스 진입점에서 하이픈을 제거해 이후 로직(중복 확인, Redis 키, SMS 발송)에
  일관되게 쓰이는 값 하나로 통일했다.

## 리뷰 반영 (PR #174, Codex 자동 리뷰)

PR을 올린 뒤 Codex 자동 리뷰가 지적한 3건을 코드로 재현 확인하고 모두 반영했다(커밋 `63f8295`).

- **P1 — 쿨다운 확인과 저장 사이 경쟁 조건**: `isInCooldown()` 확인 후 `save()`하는 두 단계 사이에 원자성이
  없어, 동시 요청(더블클릭·재시도) 둘 다 쿨다운을 통과한 뒤 서로 다른 코드를 만들어 나중 저장이 앞의 코드를
  덮어쓸 수 있었다. `VerificationCodeStore`를 `reserveCooldown()`(Redis `SET NX EX`, 인메모리는
  `Set.add()`의 원자성 이용) + `saveCode()` + `release()`로 나눠, 쿨다운 선점 자체를 원자적 단일 연산으로
  만들었다. 동시 10건 요청 중 1건만 성공하는 단위 테스트로 확인.
- **P2 — `phoneNumber` null 허용**: Bean Validation의 `@Pattern`은 null을 통과시켜서, `phoneNumber`가
  없는 요청이 서비스까지 들어가 `NullPointerException`(→ 500)이 났다. `@NotBlank`를 추가해 400으로
  거부되게 했다.
- **P2 — 발송 실패 시 Redis 정리 안 됨**: `saveCode()`가 `smsSender.sendVerificationCode()`보다 먼저
  실행돼서, 발송이 실패해도 코드·쿨다운 키가 남아 재시도가 429로 막혔다. 발송 실패 시
  `verificationCodeStore.release()`로 두 키를 모두 지우도록 했다. 실패 후 즉시 재시도가 200을 받는
  E2E 테스트로 확인.

## 일부러 하지 않은 것

- **인증번호 확인(검증) 로직**: #21의 범위. 이번엔 Redis에 코드를 저장하는 데까지만 하고, 읽어서 비교하는
  API는 만들지 않았다. 키 포맷(`phone-verification:code:{purpose}:{phoneNumber}`)은 #21이 그대로 재사용할 수 있게 맞춰뒀다.
- **실제 SMS 벤더 연동**: 이슈 비고에 "MVP에서 모의 처리 가능"이라 명시돼 있어 `LoggingSmsSender`(로그만 남김)로
  구현했다. 실패 경로(502)는 서비스 레벨에서 예외 변환 로직만 만들고, 실제로 실패를 일으키는 벤더 연동은
  다루지 않았다. — 후속: 미등록(향후 벤더 선정 이슈에서)
- **계정 찾기(FIND_ID) 목적의 "미가입 번호" 거부**: 이슈 처리 흐름 ③이 "회원가입 목적이면" 조건부라서,
  FIND_ID 목적에는 회원 존재 여부 확인을 넣지 않았다. #22(아이디 찾기) 작업 시 필요하면 그때 추가한다.
- **로컬 Docker(MySQL+Redis) 기동 상태에서의 실제 부팅 검증**: 이 세션 환경에 Docker Desktop이 떠 있지 않아
  `./gradlew bootRun --args='--spring.profiles.active=local'` + `/v3/api-docs` 확인을 실제로 하지 못했다.
  테스트는 H2/인메모리 대체로 91건 모두 통과했지만, 실제 MySQL+Redis 조합 기동은 다음 작업자(또는 로컬 환경)에서
  한 번 확인이 필요하다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `member/service/PhoneVerificationServiceTest.java` | 정상 발급, SIGNUP 중복 거부(409), FIND_ID는 중복확인 생략, 쿨다운 거부(429), SMS 실패 변환(502), 실패 후 재시도 허용, 동시 요청 10건 중 1건만 성공(경쟁 조건) |
| 통합 | `member/repository/MemberRepositoryIntegrationTest.java` | `existsByPhoneNumber`가 실제 JPA/H2(MySQL 모드)에서 가입·미가입을 정확히 구분 |
| E2E | `member/controller/PhoneVerificationE2ETest.java` | 실제 HTTP로 200/409/400/429/502 흐름, `phoneNumber` 누락 400, 발송 실패 후 재시도 200, `ApiResponse` 래핑까지 확인 |

실행 결과:

```text
cd backend && ./gradlew test
BUILD SUCCESSFUL
95 tests completed, 0 failed, 0 errors (전체 스위트, QuickApplicationTests 포함, 리뷰 반영 후 재실행)
```

### 검증하지 못한 것

- Redis 실제 TTL 만료 동작(코드 5분, 쿨다운 60초가 실제로 만료되는지) — 인메모리 대체로만 검증했다.
- 로컬 Docker(MySQL 8.4 + Redis 7.4)로 실제 기동해 `/v3/api-docs`가 정상 생성되는지 — Docker Desktop 미가동으로 스킵.
- 프론트 연동 — 이슈 범위에 화면이 없어 Orval 재생성/화면 확인은 하지 않았다.

## 새로 생긴 미결 사항

- 통합/E2E 테스트가 Redis를 인메모리 대체로만 검증함 — 실제 Redis TTL 만료 동작 확인 필요 (`CLAUDE.md`에 반영)
- 외부 SMS 발송 벤더 선정 및 `SmsSender` 실제 구현체 교체 필요 (`CLAUDE.md`에 반영)
