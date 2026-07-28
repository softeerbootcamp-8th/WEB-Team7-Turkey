# 고객 회원가입 작업 기록

- 이슈: [#25](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/25)
- 브랜치: `feature/25-customer-signup`
- 범위: backend
- 작성일: 2026-07-28

## 무엇을 만들었나

휴대전화 인증을 완료한 고객이 로그인 ID·비밀번호·이름·전화번호·약관 동의로 계정을 생성하는 API를 만들었다.
Member(role=CUSTOMER)를 저장하고, 제출된 약관 ID들에 대해 MemberTermAgreement 이력을 함께 저장한다.
휴대전화 인증 완료 토큰을 실제로 소비(조회+1회성 삭제)하는 로직이 이번에 처음 생겼다 — `#21`
워크로그에 "아직 없음"으로 남겨뒀던 것을 여기서 구현했다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| POST | `/api/customer/signup` | 고객 회원가입 | 400 형식오류·비밀번호불일치·미인증·필수약관미동의, 409 아이디·전화번호 중복 |

### 화면

해당 없음(백엔드 API만). `frontend/src/routes/customer/signup/index.tsx`에 정적 마크업은 이미 있지만
이번 이슈 범위는 API뿐이라 연동하지 않았다 — 아래 「일부러 하지 않은 것」 참고.

### 스키마 변경

해당 없음. `member`(V1), `term`(V3), `member_term_agreement`(V4) 테이블을 그대로 쓴다.
ERD에 있는 `customer` 테이블은 만들지 않기로 결정했다(「사람이 고른 선택 1」).

## 사람이 고른 선택

### 1. Customer 전용 프로필 테이블 생성 여부

- **물었던 것**: ERD(`docs/03-erd.md`)엔 `Member 1 ── 0..1 Customer`가 명시돼 있지만, 실제로 컬럼이
  하나도 확정된 게 없고 회원가입 입력값(로그인ID·비밀번호·이름·전화번호)은 전부 Member에 이미 있다.
  이번 이슈에서 빈 `customer` 테이블이라도 만들어 둘지 확인했다.
- **선택지**:
  - (A) Member만 생성 — 지금 당장 쓸 컬럼이 없는 빈 테이블을 미리 만들지 않음 / 나중에 `customer` 테이블이
    필요해지면 새 마이그레이션 추가
  - (B) `member_id` FK만 가진 빈 `customer` 테이블도 함께 생성 — ERD와 코드가 항상 일치 / 지금은 아무
    컬럼도 없어 존재 이유가 불명확한 테이블
- **고른 것**: (A)
- **근거**: "고객컬럼이 필요가없어서 따로 커스터머 테이블을 안만든거야. 실제 필요해지는시점에 개발하면됨"
- **영향**: `Customer` 엔터티/리포지토리/마이그레이션 없음. 향후 고객 전용 컬럼(예: 기본 배송지)이 필요한
  이슈에서 `V18__create_customer.sql`을 새로 추가해야 한다.

### 2. 비밀번호 형식 규칙

- **물었던 것**: 이슈 처리 흐름 ②가 "비밀번호 일치 여부와 규칙 확인"이라고만 돼 있고 구체적 규칙이 없다.
  프론트 placeholder는 "영문, 숫자, 특수문자 조합"만 암시할 뿐 최소 길이 등은 어디에도 정해져 있지 않았다.
- **선택지**:
  - (A) 영문+숫자+특수문자 조합, 8~20자 — 프론트 placeholder와 일치, 가장 흔한 정책
  - (B) 최소 길이만 검증 — 더 단순
  - (C) 형식 규칙 없음(필수값만 확인) — 구현 최소화
- **고른 것**: (C)
- **근거**: "비밀번호 규칙도 필요없어 그냥 아무렇게나 생성하게해 어차피 실사용안할거야"
- **영향**: `CustomerSignupRequest.password`는 `@NotBlank`만 걸려 있다. 비밀번호==비밀번호확인 일치 검증은
  이슈 처리 흐름에 명시된 별개 항목이라 그대로 유지했다(형식 규칙과는 다른 체크).

## 스스로 판단한 것

- **인증 완료 토큰 소비는 Redis GETDEL로**: `VerificationCodeStore.consumeVerifiedToken(token)`을 추가하고
  `RedisVerificationCodeStore`는 `opsForValue().getAndDelete(...)`로 구현했다. 조회와 삭제를 원자적으로
  묶어야 같은 토큰으로 동시에 들어온 두 요청이 하나만 성공하기 때문이다(그중 하나는 소비된 토큰을 만나
  400을 받는다). `InMemoryVerificationCodeStore`(테스트 대체)는 `Map.remove`로 동일하게 구현했다.
- **토큰 값의 purpose·전화번호까지 검증**: 토큰 값이 `"SIGNUP:01012345678"` 형태이므로, 단순히 토큰
  존재 여부만 보지 않고 `purpose == SIGNUP`인지, 요청의 전화번호와 일치하는지까지 확인한다. `#21` 워크로그가
  "FIND_ID 목적 토큰을 SIGNUP에 재사용하는 걸 막아야 한다"고 미리 남겨둔 주의사항을 그대로 반영했다.
- **DB unique 제약을 최종 방어선으로**: `existsByLoginId`/`existsByPhoneNumber` 사전 확인은 UX용이고,
  실제 동시 가입 경쟁은 `member` 테이블의 `uk_member_login_id`/`uk_member_phone_number`가 막는다.
  `memberRepository.save()`가 `DataIntegrityViolationException`을 던지면 409로 변환한다 — 이슈의
  "동시 가입 요청은 한 건만 성공한다" 요구를 코드 레벨 선점 대신 DB 제약으로 만족시켰다.
  단, 이걸 실제 두 스레드 경쟁으로 재현하는 통합 테스트는 작성하지 않았다(아래 「검증하지 못한 것」).
- **약관 검증 범위**: `TermRepository.findByActiveTrueAndTargetRoleIn(COMMON, CUSTOMER)`로 현재 활성인
  전체 약관을 가져와, (a) 요청의 `agreedTermIds`에 없는 필수 약관이 있으면 400, (b) 활성 약관 목록에 없는
  ID가 섞여 있으면 400으로 거부한다. 필수 약관 목록을 프론트가 어떻게 얻는지는 `#72`(회원가입 약관 목록
  조회)가 아직 없어 정해지지 않았다 — 아래 「새로 생긴 미결 사항」 참고.
- **패키지 위치**: `customer` 패키지에 뒀다. `#24`(아이디 중복 확인)는 고객·라이더 공통이라 `member`
  패키지에 있지만, 회원가입은 고객 전용(`#48` 라이더 회원가입이 별도 이슈)이라 `customer` 컨트롤러/서비스/DTO로
  분리했다.
- **응답 HTTP 상태는 200**: 이슈 성공 조건 문구만 보면 201도 고려했지만, 이 저장소의 기존 컨트롤러
  (`PhoneVerificationController`, `LoginIdController`)가 전부 `ApiResponse<T>`를 그대로 반환해 암묵적으로
  200을 쓰는 관례라 거기 맞췄다.

## 일부러 하지 않은 것

- **Customer 전용 프로필 테이블**: 「사람이 고른 선택 1」에서 뺐다. 후속: 미등록(실제로 고객 전용 컬럼이
  필요해지는 이슈에서 새로 시작).
- **로그인 세션 자동 발급**: 이슈 성공 조건은 "회원가입 성공 결과가 반환된다"까지만 요구한다. 로그인은
  `#26`(고객 로그인)이 별도 이슈라 회원가입 응답에 세션 쿠키를 심지 않았다.
- **비밀번호 형식 규칙**: 「사람이 고른 선택 2」에서 뺐다.
- **`#24`(아이디 중복 확인) API 재사용**: `#24` 워크로그에 이미 "`#25`는 독립적으로 다시 확인한다"고
  명시돼 있어, `GET /api/login-ids/availability`를 호출하지 않고 `MemberRepository.existsByLoginId`를
  직접 썼다.
- **프론트 연동**: 정적 마크업(`customer/signup/index.tsx`)은 있지만 Orval 훅 연결, 아이디 중복 확인·인증번호
  발송 버튼 배선은 하지 않았다. 후속: 미등록(별도 프론트 연동 이슈 필요 여부를 팀에서 정해야 한다).

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `customer/service/CustomerSignupServiceTest.java` | 정상 가입(비밀번호 해시·약관 동의 저장), 비밀번호 불일치, 아이디·전화번호 중복, 인증 토큰 없음/1회성 소비/목적 불일치/전화번호 불일치, 필수 약관 미동의, 존재하지 않는 약관 ID, DB 유니크 위반 시 409 변환 |
| E2E | `customer/controller/CustomerSignupE2ETest.java` | 실제 인증요청→확인→가입 전 경로, 필수 약관 미동의 400, 필수 약관 동의 시 가입 성공, 아이디 중복 409, 미인증 상태 400, 비밀번호 불일치 400 |

통합 계층은 별도 파일을 추가하지 않았다 — `existsByLoginId`/`existsByPhoneNumber`는 기존
`MemberRepositoryIntegrationTest`(`#24`)가 이미 덮고, `TermRepository`/`MemberTermAgreementRepository`는
E2E 테스트가 실제 H2(Flyway 마이그레이션 포함)로 왕복 검증한다.

실행 결과:

```text
cd backend && ./gradlew test
BUILD SUCCESSFUL
128 tests completed, 0 failed, 0 errors (전체 스위트, #20/#21/#24 포함)
```

(중간에 `java.net.SocketException: Bad address: listen`으로 2회 실패했으나 재시도 시 정상 통과했다 —
RANDOM_PORT 테스트 서버 바인딩과 관련된 로컬 환경 이슈로 보이며 코드 변경과는 무관하다.)

### 검증하지 못한 것

- **동시 가입 경쟁의 실제 멀티스레드 재현**: `existsByLoginId` 통과 후 `save()`가
  `DataIntegrityViolationException`을 던지는 경로는 단위 테스트에서 예외를 강제로 발생시켜서만 확인했다.
  `#20`(휴대전화 인증)처럼 두 스레드가 실제로 경쟁하는 통합 테스트는 작성하지 않았다.
- 로컬 Docker(MySQL+Redis) 실기동 확인 — 이전 이슈들과 같은 이유로 스킵.

## 새로 생긴 미결 사항

- 필수 약관 목록을 프론트가 어떻게 조회해 `agreedTermIds`를 채울지는 `#72`(회원가입 약관 목록 조회)가
  아직 구현되지 않아 정해지지 않았다. `#72` 구현 시 이 API가 기대하는 "활성 + COMMON/CUSTOMER 대상" 약관
  집합과 응답 스키마(특히 `termId`)를 맞춰야 한다.
- 회원가입 동시 요청(같은 아이디로 동시 가입)에 대한 실제 멀티스레드 통합 테스트가 없다. DB unique 제약과
  예외 변환 로직 자체는 있지만, 경쟁 상황 재현 테스트로 검증되지는 않았다.
