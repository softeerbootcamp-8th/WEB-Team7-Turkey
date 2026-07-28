# 고객 로그인 상태 확인 작업 기록

- 이슈: [#27](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/27)
- 브랜치: `feature/27-customer-session-check`
- 범위: backend
- 작성일: 2026-07-28

## 무엇을 만들었나

`SESSION_ID` 쿠키로 현재 세션을 검증하고 인증된 고객 정보를 반환하는 `GET /api/customer/session`을
만들었다. 이 저장소에서 세션 검증(매 요청마다 쿠키를 확인하는) 로직이 처음 생기는 지점이라,
`customer` 패키지에 `HandlerInterceptor` 기반 인증 인프라(`customer/auth`, `customer/config`)를
새로 추가했다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/customer/session` | 세션 쿠키 검증 후 고객 정보 반환 | 401(쿠키 없음·세션 없음/만료·역할 불일치·비활성 계정 — 전부 동일 메시지) |

### 화면

해당 없음(백엔드 API만). 새로고침 시 로그인 상태 복구 화면 연동은 이번 범위 밖.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 세션 인증 로직을 어디에 둘까

- **물었던 것**: #26 워크로그가 "#27이 필터/인터셉터가 처음 생기는 지점"이라고 미리 적어뒀지만,
  실제로 (A) 재사용 가능한 공용 인터셉터로 지금 추출할지 (B) 이번 엔드포인트 서비스 안에 로직을
  그대로 두고 다음 보호된 엔드포인트가 나올 때 추출할지는 다시 확인이 필요했다. 확인 과정에서
  "왜 인터셉터고 필터가 아니냐"는 질문이 나와, 이 저장소에 이미 있는 `RequestLoggingFilter`(필터)
  선례와 비교해 설명했다.
- **선택지**:
  - (A) 공용 인터셉터로 지금 추출 — 향후 "고객 전용 API"가 나올 때 재사용 가능하지만 지금 조금
    더 일함
  - (B) 이번 서비스 안에 직접 구현 — 지금은 빠르지만 다음 보호된 엔드포인트가 생길 때 다시 추출
    작업 필요
  - (C, 질문에서 파생) 필터로 구현 — 이 저장소의 기존 선례(`RequestLoggingFilter`)와 형태는
    맞지만, `Filter`는 `DispatcherServlet` 바깥(서블릿 컨테이너 레벨)에서 돌기 때문에 필터 안에서
    던진 예외를 `GlobalExceptionHandler`(`@RestControllerAdvice`)가 잡지 못한다 — `ApiResponse`
    에러 포맷을 유지하려면 필터 안에서 직접 JSON을 만들어야 해서 기존 예외 처리 컨벤션과 다른
    경로가 하나 더 생긴다. `HandlerInterceptor.preHandle()`은 `DispatcherServlet.doDispatch()`의
    try 블록 안에서 실행되므로, 여기서 던진 `BusinessException`도 기존 `GlobalExceptionHandler`가
    그대로 잡아 처리한다(추가 코드 없음).
- **고른 것**: (A) 공용 인터셉터, 예외 처리 컨벤션과의 정합성 때문에 필터가 아니라 인터셉터로.
- **근거**: 필터는 예외 처리 경로가 하나 더 생긴다는 게 실질적 단점이라는 데 동의함("필터는
  경로가 하나더 생기니까 별로인거같은데 인터셉터가 더 나은거 아니야?").
- **영향**: `customer/auth/CustomerSessionInterceptor`(고객 전용, `MemberRole.CUSTOMER` 확인)로
  구현. 다만 "지금 바로 다중 액터 재사용 가능하게 일반화"까지는 하지 않았다 — `common/auth`에
  두면 `SessionStore`처럼 도메인 enum(`MemberRole`)에 의존하지 않게 설계해야 하는데, 아직
  라이더 쪽 소비자가 없는 시점에 그 추상화를 미리 만드는 건 과설계로 판단해 `customer` 패키지
  안에 뒀다(「스스로 판단한 것」 참고). 라이더 로그인 상태 확인 이슈(`#49~#52` 대응)가 생기면
  같은 패턴으로 `rider/auth/RiderSessionInterceptor`를 별도로 만들면 된다.

### 2. 엔드포인트 경로

- **물었던 것**: 이슈 본문에 경로가 명시돼 있지 않았다.
- **선택지**:
  - (A) `GET /api/customer/session` — 이슈 제목("로그인 상태 확인")과 직접 대응, 세션 자체를
    조회한다는 의미가 명확
  - (B) `GET /api/customer/me` — REST 관용구(자기 정보 조회)에 더 가까움
- **고른 것**: (A)
- **근거**: "GET /api/customer/session (추천)" 그대로 선택.
- **영향**: `CustomerSessionController`의 `@RequestMapping("/api/customer/session")`.

### 3. 조회 시 세션 TTL 슬라이딩 갱신 여부

- **물었던 것**: #26 워크로그가 이 결정을 #27로 명시적으로 미뤄뒀다.
- **선택지**:
  - (A) 갱신 안 함 — TTL은 로그인 시점 2시간 고정 유지. 새로고침을 자주 해도 세션이 늘어나지
    않아 동작이 예측 가능하다. 나중에 필요해지면 별도 이슈로 추가하기 쉽다.
  - (B) 조회할 때마다 TTL 연장 — 활동이 있으면 세션이 계속 유지된다(사용자 편의 우선). 다만
    "로그아웃 안 해도 영원히 로그인 유지"가 될 수 있어 만료 정책이 흐려진다.
- **고른 것**: (A)
- **근거**: "갱신 안 함 (추천)" 그대로 선택.
- **영향**: `SessionStore.findMemberId()`는 순수 조회만 하고 TTL을 건드리지 않는다(Redis
  구현은 `HGET`만 쓰고 `EXPIRE`를 다시 호출하지 않음). 슬라이딩 갱신이 필요해지면 별도 이슈로
  다룬다.

## 스스로 판단한 것

- **`common/auth`가 아니라 `customer/auth`에 인터셉터를 둠**: `SessionStore`/`SessionCookie`는
  고객·라이더 공용이라 `common/auth`에 있는 게 맞지만, "세션을 검증해서 인증된 회원을 만드는"
  로직 자체는 지금 소비자가 고객 하나뿐이다. `common/auth`에 넣으려면 `SessionStore`처럼
  `MemberRole` 대신 문자열 role만 다루도록 설계해야 하는데, 아직 실제로 재사용할 라이더 쪽
  코드가 없는 시점에 그 추상화를 미리 만드는 건 이슈에 없는 일반화라고 판단했다(mvp-feature
  스킬의 "이슈에 없는 기능을 미리 덧붙이지 않는다" 원칙). `SessionCookie.NAME` 같은 진짜 공용
  상수만 `common/auth`에 남기고, 인터셉터·`MemberRole` 확인 로직은 `customer` 패키지 안에 뒀다.
- **인터셉터가 request attribute에 JPA 엔티티(`Member`) 대신 `AuthenticatedCustomer` 레코드를
  담음**: 이 저장소의 다른 컨트롤러들은 전부 서비스 계층의 Result/Response 레코드만 다루고
  엔티티에 직접 의존하지 않는다. 인터셉터가 이미 `Member`를 조회한 김에 바로 넘길 수도 있었지만,
  같은 경계 원칙을 지키려고 `customer/auth/AuthenticatedCustomer`로 한 번 변환해서 넘겼다.
- **`SessionStore.findMemberId()`가 role을 반환하지 않음**: Redis 세션 값엔 role이 이미
  저장돼 있지만(#26, ERD 5절), role·활성 상태 확인은 세션 스냅샷이 아니라 매번 최신 `Member`
  DB 조회로 한다 — 세션 생성 이후 계정이 탈퇴됐을 수 있어서다(이슈 처리흐름 ⑤가 요구하는 바이기도
  하다). 그래서 `findMemberId()`는 실제로 쓰이는 `memberId`만 반환하도록 최소화했다(HGETALL이
  아니라 HGET 한 필드만 조회).
- **"세션 없음"과 "세션 만료"를 같은 코드 경로로 처리**: Redis TTL이 지나면 키가 자동으로
  사라지므로, 애플리케이션 입장에서 "존재하지 않는 세션"과 "만료된 세션"은 `findMemberId()`가
  `Optional.empty()`를 반환하는 동일한 결과로 관측된다. 이슈 예외 처리 목록엔 둘이 별도 항목으로
  적혀 있지만 응답도 어차피 동일한 401이라 별도 분기를 만들지 않았다.
- **실패 사유를 구분하지 않고 전부 401 + 동일 메시지("로그인이 필요합니다.")**: #26 로그인과
  같은 이유 — 이슈 비고에 계정·세션 상태를 구체적으로 노출하지 않는다는 원칙이 이어진다고 판단.

## 일부러 하지 않은 것

- **`/api/customer/**` 전체에 인터셉터를 등록하는 것**: 로그인·회원가입은 인증이 필요 없는 공개
  API라 broad 등록을 하면 실수로 막힐 위험이 있다. 지금은 이 이슈가 만드는
  `/api/customer/session` 경로 하나만 등록했다. 다음에 인증이 필요한 고객 API(예: 배송요청
  생성)가 생기면 그 경로를 `CustomerWebMvcConfig.addPathPatterns`에 추가하면 된다.
- **`@CurrentMember` 커스텀 어노테이션 + `HandlerMethodArgumentResolver`**: 스프링 내장
  `@RequestAttribute`로 `AuthenticatedCustomer`를 바로 바인딩할 수 있어 별도 리졸버를 만들
  필요가 없었다.
- **세션 만료를 별도로 시뮬레이션하는 테스트**: 위 "스스로 판단한 것"에 적었듯 "세션 없음"과
  "세션 만료"가 같은 코드 경로라 `존재하지_않는_세션이면_401을_반환한다` 테스트가 사실상 만료
  케이스도 검증한다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `customer/auth/CustomerSessionInterceptorTest.java` | 정상 세션 통과 + request attribute 반영, 쿠키 없음, 존재하지 않는 세션, 세션은 있으나 회원 없음, 라이더 세션으로 접근, 세션 생성 이후 탈퇴 |
| E2E | `customer/controller/CustomerSessionE2ETest.java` | 실제 로그인→세션 쿠키 재사용해 200+고객 정보, 쿠키 없음 401, 존재하지 않는 세션 401, 로그인 이후 탈퇴 401, 라이더 세션으로 접근 401 |

실행 결과:

```text
cd backend && ./gradlew test
BUILD SUCCESSFUL
156 tests completed, 0 failed, 0 errors (전체 스위트, #20/#21/#24/#25/#26 포함)
```

### 검증하지 못한 것

- 실제 Redis TTL 만료 후 `HGET`이 정말 빈 값을 반환하는지는 `InMemorySessionStore`(TTL 미구현)로는
  검증 범위 밖 — #20 때부터 이어지는 동일한 미검증 항목이다.
- 로컬 Docker(MySQL+Redis) 실기동 확인 — 이전 이슈들과 같은 이유로 스킵.

## 새로 생긴 미결 사항

- 라이더 쪽 로그인 상태 확인(`#49~#52` 대응)이 생기면 `customer/auth/CustomerSessionInterceptor`와
  거의 동일한 코드가 `rider/auth`에 또 생긴다 — 그 시점에 실제로 중복이 확인되면 공용 추출을
  다시 검토한다(지금은 소비자가 하나뿐이라 미룸).
- 세션 슬라이딩 갱신 정책은 이번에 "안 함"으로 확정했지만, 프론트가 새로고침마다 이 API를
  호출하는 흐름이 실제로 붙으면 "너무 자주 로그아웃된다"는 피드백이 나올 수 있다 — 그때 별도
  이슈로 재검토한다.
