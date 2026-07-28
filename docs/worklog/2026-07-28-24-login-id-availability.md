# 아이디 중복 확인 작업 기록

- 이슈: [#24](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/24)
- 브랜치: `feature/24-login-id-availability`
- 범위: backend
- 작성일: 2026-07-28

## 무엇을 만들었나

로그인 ID의 실시간 사용 가능 여부를 확인하는 조회 API를 만들었다. `#25`(회원가입)와는 독립적인 API로,
`#25`는 이 API를 호출하지 않고 자체적으로 다시 중복 확인을 수행한다(이슈 비고에 명시).

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/login-ids/availability?loginId=xxx` | 로그인 ID 중복 확인 | 400 loginId 누락/공백 |

### 화면

해당 없음 (백엔드 API만).

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 로그인 ID 형식(길이·허용 문자) 검증 여부

- **물었던 것**: 이슈 원문은 "① 로그인 ID 길이와 허용 문자 형식 검증"을 처리 흐름 1단계로 명시하고 있었지만,
  구체적인 길이·문자 규칙이 적혀 있지 않았다. 제안한 규칙(영문 소문자/숫자/언더스코어, 4~20자, 시작 문자
  제한 여부)에 대해 되물어왔다.
- **선택지**:
  - (A) 형식 규칙을 정해서 검증 (400으로 거부) — 이슈 문구 그대로 구현 / 규칙값을 새로 정해야 함
  - (B) 형식 검증 없이 중복 여부만 확인 — 구현이 단순함 / 이슈의 ①단계를 빼는 것이므로 이슈 자체를 바꿔야 함
- **고른 것**: (B)
- **근거**: "아이디 형식 제한 굳이 필요 없는 것 같다"는 판단. 형식 제약이 서비스에 실질적 가치가 없다고
  본 것으로 이해했다.
- **영향**: 코드뿐 아니라 **이슈 #24 본문 자체를 수정**했다(사용자가 "문서도 수정해줘"라고 명시적으로
  요청) — 처리 흐름에서 형식 검증 단계를 빼고, 예외 처리에서 "로그인 ID 형식 오류" 항목을 지웠다. 비고에
  결정 사실과 날짜를 남겼다. `GlobalExceptionHandler`도 형식 오류를 위한 별도 케이스 없이, 빈 값만
  `@NotBlank`로 막는다(완전히 무제한은 아니고 "빈 문자열 조회는 의미 없다"는 최소한의 방어).

## 스스로 판단한 것

- **GET + 쿼리 파라미터**: `#20`/`#21`은 부작용이 있는 POST였지만, 이건 순수 조회라 GET이 맞다고 판단했다.
  이 저장소에 GET+쿼리파라미터 형태의 첫 엔드포인트라, `@RequestParam`에 Bean Validation을 걸려면
  컨트롤러 클래스에 `@Validated`가 필요하고, 그 실패는 `MethodArgumentNotValidException`이 아니라
  `ConstraintViolationException`으로 온다는 걸 확인하고 `GlobalExceptionHandler`에 케이스를 추가했다.
- **성공/실패를 HTTP 상태가 아니라 응답 바디로 표현**: 이슈가 "사용 불가 상태와 사유를 반환한다"고 해서,
  중복인 경우도 예외를 던지지 않고 `200 OK` + `{available: false, reason: "..."}`으로 응답한다. `#20`/`#21`의
  409/404 같은 예외 기반 설계와는 다른데, 이건 "이미 가입된 번호로 회원가입을 *시도*하면 거부"(#20)와
  달리 이번엔 "사용 가능한지 물어보는" 순수 조회라 실패가 아니라 정상적인 조회 결과 중 하나라고 봤다.
- **`available`/`ofAvailable`/`ofUnavailable` 네이밍**: 레코드 컴포넌트 `available`과 이름이 겹쳐서
  정적 팩터리 메서드를 `available()`로 못 만든다(자바 record가 접근자 이름 충돌을 컴파일 에러로 막음).
  그래서 `ofAvailable()`/`ofUnavailable(reason)`으로 바꿨다.

## 일부러 하지 않은 것

- **로그인 ID 형식 검증**: 위 「사람이 고른 선택 1」에서 뺐다. `#25`(회원가입) 쪽에서도 비밀번호 규칙 등
  다른 형식 검증은 하겠지만, 로그인 ID 자체의 길이·문자 제한은 이 서비스 전체에서 없는 것으로 결정됐다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `member/service/LoginIdAvailabilityServiceTest.java` | 미사용 ID는 available=true, 사용중 ID는 available=false+사유 |
| 통합 | `member/repository/MemberRepositoryIntegrationTest.java` | `existsByLoginId`가 실제 JPA/H2에서 가입·미가입을 정확히 구분 |
| E2E | `member/controller/LoginIdE2ETest.java` | 실제 HTTP로 200(available true/false), loginId 공백 시 400 |

실행 결과:

```text
cd backend && ./gradlew test
BUILD SUCCESSFUL
110 tests completed, 0 failed, 0 errors (전체 스위트, #20/#21 포함)
```

### 검증하지 못한 것

- 로컬 Docker(MySQL+Redis) 실기동 확인 — 이전 이슈들과 같은 이유(이 세션 환경에 Docker Desktop 미가동)로 스킵.
  (참고: Docker 사용 제약 자체는 이번 세션에서 `CLAUDE.md`에서 삭제됨 — `chore/remove-docker-restriction`)

## 새로 생긴 미결 사항

- 없음 — 이 이슈 자체는 범위가 작아 새 미결 사항을 만들지 않았다.
