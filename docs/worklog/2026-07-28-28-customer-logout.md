# 고객 로그아웃 작업 기록

- 이슈: [#28](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/28)
- 브랜치: `feature/28-customer-logout`
- 범위: backend
- 작성일: 2026-07-28

## 무엇을 만들었나

`POST /api/customer/logout` — 세션 쿠키가 가리키는 Redis 세션을 삭제하고, 브라우저가 즉시
지우도록 만료 쿠키(`Max-Age=0`)를 응답한다. 이슈 예외 처리 명세대로 쿠키가 없거나 이미
만료·존재하지 않는 세션이 전달돼도 항상 200으로 처리한다(멱등).

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| POST | `/api/customer/logout` | 서버 세션 삭제 + 쿠키 만료 | 없음(항상 200, 실패 케이스 없음) |

### 화면

해당 없음(이번 이슈는 백엔드만). 이슈 비고엔 "고객 화면에 로그아웃 버튼을 제공한다"가 있지만,
그 버튼이 들어갈 고객 인증 레이아웃(`customer/_authed.tsx`)이 아직 스텁 상태라 화면 작업은
범위에서 뺐다(「사람이 고른 선택」 참고).

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 이슈 범위 — 백엔드만 vs 화면 버튼까지

- **물었던 것**: 이슈 비고에 "고객 화면에 로그아웃 버튼을 제공한다"가 명시돼 있어 #25~#27과
  달리 fullstack으로 보였다. 다만 그 버튼이 들어갈 고객 인증 레이아웃(`customer/_authed.tsx`,
  `account/settings.tsx`)이 아직 `"Hello ..."` 스텁이라, 버튼을 실제로 넣으려면 헤더·네비게이션
  같은 주변 레이아웃까지 이 이슈에서 새로 지어내야 하는 상황이었다.
- **선택지**:
  - (A) 백엔드만 먼저 — API만 만들고, 버튼은 실제 인증 레이아웃 화면이 디자인될 때 붙인다
  - (B) 지금 fullstack으로 — API + `_authed.tsx`에 최소한의 헤더/로그아웃 버튼을 지금 만든다
- **고른 것**: (A)
- **근거**: "백엔드만 먼저 (추천)" 선택.
- **영향**: 프론트 로그아웃 버튼 연동은 이번 범위 밖(「일부러 하지 않은 것」).

### 2. 구현 복잡도 — 서비스 계층 필요 여부

- **물었던 것**: 명시적으로 물은 건 아니지만, 구현 중간에 "로그아웃은 별로 안 중요하니까
  간단하게 해달라"는 요청을 받아 원래 만들어뒀던 `CustomerLogoutService`(세션 ID null 체크 후
  `SessionStore.delete` 호출하는 한 줄짜리 서비스)를 걷어냈다.
- **고른 것**: 서비스 계층 없이 `CustomerLogoutController`가 `SessionStore`를 직접 호출.
- **근거**: "로그아웃은 별로안중요하긴함 간단하게 해줘."
- **영향**: `CustomerLogoutServiceTest`도 함께 삭제하고, E2E 테스트만으로 동작을 검증한다.
  다른 고객 액션들(`CustomerLoginService`, `CustomerSignupService`)과 계층 구조가 달라졌지만,
  로직이 조건 하나(`sessionId != null`)뿐이라 서비스로 감쌀 만큼의 복잡도가 없다고 판단했다.

## 스스로 판단한 것

- **`SessionCookie`에 `expired()`/`extractSessionId()`를 추가**: `expired()`는 #26 워크로그가
  이미 "#28(로그아웃)이 같은 속성으로 쿠키를 만료시켜야 한다"고 예고해둔 그대로, 로그인 발급
  쿠키와 동일한 속성(`HttpOnly`/`SameSite=Lax`/`Secure`/`Path=/`)에 `Max-Age=0`만 다르게 준다.
  `extractSessionId()`는 원래 `CustomerSessionInterceptor`(#27) 안에 private 메서드로 있던
  쿠키 파싱 로직을, 로그아웃 컨트롤러도 똑같이 필요로 하게 되면서 `SessionCookie`로 옮겼다 —
  소비자가 둘이 되는 시점에 추출한 것이라 미리 만든 추상화는 아니다.
- **`SessionStore.delete()`가 존재하지 않는 세션 ID에도 예외를 던지지 않음**: Redis `DEL`도,
  `ConcurrentHashMap.remove()`도 원래 없는 키에 대해 조용히 아무 일도 안 하므로, 이슈가 요구하는
  멱등성이 별도 코드 없이 자연히 만족된다.
- **로그아웃 엔드포인트에 `CustomerSessionInterceptor`를 걸지 않음**: 이슈 예외 처리가 "세션이
  없거나 만료됐어도 로그아웃 완료로 처리"라고 명시하므로, 이 엔드포인트는 애초에 인증을 요구하면
  안 된다. `CustomerWebMvcConfig`의 `addPathPatterns`에도 이 경로를 추가하지 않았다.

## 일부러 하지 않은 것

- **고객 화면 로그아웃 버튼**: 「사람이 고른 선택 1」 참고. 인증 레이아웃이 실제로 디자인되는
  시점에 별도로 연결한다.
- **로그아웃 시 응답 바디에 안내 메시지 등 추가 정보**: 이슈가 요구하지 않아 `ApiResponse.ok(null)`만
  반환한다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| E2E | `customer/controller/CustomerLogoutE2ETest.java` | 로그인 후 로그아웃 시 200+만료 쿠키(Max-Age=0)+세션 스토어에서 실제 삭제, 로그아웃 이후 `/api/customer/session` 재호출 시 401(#27과의 연동 검증), 쿠키 없이 호출해도 200, 존재하지 않는 세션이어도 200 |

실행 결과:

```text
cd backend && ./gradlew test
BUILD SUCCESSFUL
162 tests completed, 0 failed, 0 errors (전체 스위트, #20/#21/#24/#25/#26/#27 포함)
```

(중간에 `java.net.SocketException: Bad address: listen` — RANDOM_PORT 포트 바인딩 flaky로
2회 재실행 후 성공. 이전 이슈들에서도 반복된 환경 이슈로, 코드와 무관함을 확인했다.)

### 검증하지 못한 것

- 로컬 Docker(MySQL+Redis) 실기동 확인 — 이전 이슈들과 같은 이유로 스킵.

## 새로 생긴 미결 사항

- 고객 인증 레이아웃(`customer/_authed.tsx`)이 디자인되면 로그아웃 버튼을 이 API에 연결하는
  프론트 작업이 필요하다(후속 이슈 미등록 — 화면 작업 이슈가 생기면 그때 연결).
