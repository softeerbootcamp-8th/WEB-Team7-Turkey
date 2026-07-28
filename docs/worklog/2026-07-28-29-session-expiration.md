# 세션 만료 처리 작업 기록

- 이슈: [#29](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/29)
- 브랜치: `feature/29-session-expiration`
- 범위: backend
- 작성일: 2026-07-28

## 무엇을 만들었나

이슈의 처리 흐름(① 쿠키 확인 ② 서버 세션 조회 ③ 만료 확인 ④ 만료 세션 삭제 ⑤ 쿠키 만료 응답
+ 인증 실패 반환) 중 ①~④는 이미 `#27`(인터셉터)·`#26`(Redis TTL)에서 구현돼 있었다. 실제로
빠져 있던 건 ⑤의 절반 — 인증 실패 시 인증 실패 **결과**는 반환했지만 **세션 쿠키 만료 응답**은
같이 보내지 않고 있었다. `CustomerSessionInterceptor`(#27)가 인증에 실패할 때마다
`#28`에서 만든 `SessionCookie.expired()`를 응답에 실어 보내도록 수정했다.

### API

기존 API(`GET /api/customer/session`, #27)의 실패 응답 헤더만 바뀐다. 새 엔드포인트 없음.

- 401 응답에 `Set-Cookie: SESSION_ID=; Max-Age=0; ...`가 추가된다(기존엔 본문만 401이었음).

### 화면

해당 없음.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 이슈 범위 해석 — ①~④가 이미 구현돼 있다는 판단

- **물었던 것**: 명시적으로 사람에게 물은 건 아니고, 이슈를 읽자마자 "④ 만료된 세션 삭제" 단계가
  기존 구현(Redis TTL 자동 삭제)과 겹치는 걸로 보여 그 해석을 사람에게 먼저 설명했다.
- **선택지**:
  - (A) 이슈를 문자 그대로 읽어 "만료 여부를 애플리케이션이 직접 판단하고 삭제하는 코드"를
    새로 만든다 — `SessionStore`가 저장한 `expiresAt`을 읽어 `now`와 비교하는 로직 추가
  - (B) Redis TTL이 이미 "판단+삭제"를 자동으로 수행하는 것으로 보고(#27에서 이미 확정한 해석),
    이 이슈의 net-new 요구는 "⑤ 쿠키 만료 응답"뿐이라고 좁게 잡는다
- **고른 것**: (B) — 대화에서 명시적으로 반박 없이 이 해석대로 진행에 동의함("진행해줘").
- **근거**: `expiresAt`을 애플리케이션이 다시 읽어 비교하는 (A)는 Redis TTL과 별도로 만료
  판단 로직을 하나 더 두는 것이라, 두 소스(Redis TTL vs 앱이 읽은 expiresAt)가 어긋날 경우
  버그의 원인이 된다. Redis TTL을 유일한 판단 기준으로 유지하는 게 `#26`/`#27`에서 이미 확정한
  방향과 일치한다.
- **영향**: `SessionStore`에 만료 판단용 메서드를 추가하지 않았다. `findMemberId()`가
  `Optional.empty()`를 반환하는 것 자체가 "세션 없음 = 만료 포함"이라는 기존 해석을 그대로
  유지한다.

## 스스로 판단한 것

- **인터셉터의 4개 실패 분기를 `authFailure(response)` 헬�터 메서드로 통합**: 실패 사유마다
  `throw new BusinessException(...)`을 반복하던 걸, 만료 쿠키 헤더 추가까지 같이 하는 공통
  메서드로 묶었다. 그렇게 하지 않으면 쿠키 추가 코드를 4곳에 복붙해야 해서 하나라도 빠뜨리기
  쉬웠다.
- **쿠키가 애초에 없었던 요청에도 만료 쿠키를 응답에 포함시킴**: 이슈 사전 조건은 "세션 쿠키를
  포함하여 요청한 상태"를 전제하지만, 실무적으로 쿠키가 아예 없는 요청에 만료 쿠키를 보내는 건
  무해하고(브라우저가 없는 쿠키를 지우라는 지시를 받아도 아무 일도 안 함), 실패 분기마다 다르게
  처리하면 코드가 더 복잡해진다. 그래서 4개 실패 분기 모두 동일하게 처리했다.
- **`CustomerSessionInterceptor` 생성자에 `cookieSecure`(boolean)를 추가**: 인터셉터가 직접
  Spring 빈이 아니라 `CustomerWebMvcConfig`가 `new`로 생성해 등록하는 구조(#27)라 `@Value`를
  인터셉터 자신에 붙일 수 없다. `CustomerLoginController`/`CustomerLogoutController`와 같은
  방식으로 `CustomerWebMvcConfig`(빈)에 `@Value("${session.cookie.secure:true}")`를 두고
  생성자로 넘겼다.
- **회원 상태(탈퇴 등)로 인증에 실패한 경우에도 Redis 세션 자체는 삭제하지 않음**: 세션은 아직
  TTL이 안 지나 Redis에 남아 있지만 회원이 탈퇴해 인증이 거부되는 경우, 그 세션을 즉시
  `SessionStore.delete()`로 지우는 것도 고려했지만 이슈 범위 밖(이슈의 "④ 만료된 세션 삭제"는
  TTL 만료를 가리키는 것으로 해석했다 — 「사람이 고른 선택 1」)이라 하지 않았다. 클라이언트
  쿠키는 어차피 이번 응답으로 만료되므로 같은 쿠키로의 재요청은 어차피 다시 401을 받는다 —
  실질적 위험은 낮다고 판단했다.

## 일부러 하지 않은 것

- **`expiresAt` 필드를 애플리케이션이 직접 읽어 비교하는 만료 판단 로직**: 「사람이 고른 선택 1」
  참고. Redis TTL을 유일한 판단 기준으로 유지한다.
- **회원 상태 문제로 인증 실패한 세션의 즉시 삭제**: 「스스로 판단한 것」 참고 — 이슈 범위 밖으로
  판단, 필요해지면 별도 이슈로 다룬다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `customer/auth/CustomerSessionInterceptorTest.java` | (기존 6개 + 신규) 인증 실패 시 응답에 만료 쿠키(`SESSION_ID=`, `Max-Age=0`)가 포함되는지 |
| E2E | `customer/controller/CustomerSessionE2ETest.java` | (기존 + 신규) 만료/존재하지 않는 세션으로 접근 시 401과 함께 만료 쿠키 응답 확인 |

실행 결과:

```text
cd backend && ./gradlew test
BUILD SUCCESSFUL
164 tests completed, 0 failed, 0 errors (전체 스위트, #20/#21/#24/#25/#26/#27/#28 포함)
```

(1회차 실행에서 `CustomerSignupE2ETest`가 `java.net.SocketException: Bad address: listen`으로
실패 — RANDOM_PORT 바인딩 flaky, 이전 이슈들에서도 반복된 환경 이슈. 재실행 후 전부 통과.)

### 검증하지 못한 것

- 실제 Redis TTL이 만료되는 순간 `HGET`이 빈 값을 반환하는지는 `InMemorySessionStore`로는
  검증 범위 밖 — #20부터 이어지는 동일한 미검증 항목.
- 로컬 Docker(MySQL+Redis) 실기동 확인 — 이전 이슈들과 같은 이유로 스킵.

## 새로 생긴 미결 사항

- 없음. 이 이슈로 auth 도메인 P0 이슈(#20/#21/#24~#29)가 모두 끝난다.
