# 데모데이 후속 조치 7건 작업 기록

- 이슈: [#531](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/531)
  [#532](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/532)
  [#533](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/533)
  [#534](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/534)
  [#535](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/535)
  [#536](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/536)
  [#537](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/537)
- 브랜치: `claude/demo-day-network-disconnect-n3lxni`
- 범위: fullstack (backend + frontend + mobile android)
- 작성일: 2026-08-14

**「이슈당 한 파일」 원칙에서 의도적으로 벗어남**: 데모데이 피드백 7건을 사람이 한 세션에서 연속
처리해 달라고 요청했다. 각 항목이 대체로 작아 개별 워크로그 7개보다 이 한 파일이 검토하기 쉽다고
판단해 묶었다 — 다음에 이 중 하나를 더 파고들 일이 생기면 그때 개별 파일로 분리한다.

## 실행 환경 제약 (전체에 영향)

이 세션은 Docker 데몬이 없는 샌드박스에서 진행됐다(`docker info`가 소켓 연결 실패, Android SDK도
없음). 그 결과:

- 백엔드를 로컬로 못 띄워 **Orval `pnpm generate:api`를 못 돌렸다** — 백엔드 DTO에 새 필드를
  추가한 두 항목(#532, #533)은 백엔드는 완성·테스트했지만 프론트가 그 필드를 아직 못 받는다
  (`src/api/generated/`는 생성물이라 손으로 못 고침, 프론트 CLAUDE.md).
- Android(`mobile/android`)는 Gradle 의존성 저장소(`dl.google.com`)가 프록시에서 403으로 막혀
  실제 컴파일 검증을 못 했다. 코드 리뷰로는 API 시그니처를 재확인했지만 실기기·에뮬레이터 확인은
  못 했다.
- 백엔드 통합·E2E 테스트(`@SpringBootTest` + 실제 MySQL/Redis)도 같은 이유로 못 돌렸다. 순수
  단위 테스트(Mockito, HTTP 스텁 등 외부 인프라 불필요한 48개 클래스)는 전부 통과했다.

## 무엇을 만들었나

### #536 — 연락처 입력 한글 방지

- 배송요청 생성의 보내는/받는 분 전화번호(`ContactRequest.phoneNumber`)에 `@Pattern` 검증이 아예
  없었다 — 회원가입 DTO들과 달리 형식 검증 없이 `@NotBlank @Size(max=20)`뿐이었다. 같은 정규식
  (`^01(?:0|1|[6-9])-?\d{3,4}-?\d{4}$`)을 추가했다.
- 프론트 `DeliveryForm`의 연락처 입력은 숫자·하이픈 외 문자를 즉시 걸러내도록(`replace(/[^0-9-]/g, '')`)
  바꾸고, `validateDeliveryForm`에도 같은 형식 검증을 추가했다.

### #537 — 비밀번호 8자 이상

- `CustomerSignupRequest`·`RiderSignupRequest`의 `password`에 `@Size(min = 8)` 추가.
- 프론트 두 회원가입 폼의 `validate*Signup`에 동일 규칙, placeholder에 "(8자 이상)" 안내 추가.
- 기존 E2E 테스트가 전부 `"aaa"`(3자)를 자리표시자로 썼던 걸 `"p@ssw0rd"`(8자)로 교체 — 안 그러면
  "약관 미동의 400", "아이디 중복 409" 같은 원래 검증하려던 사유가 아니라 비밀번호 길이 때문에
  400이 나서 테스트가 우연히 통과하는 상태가 된다.

### #531 — 안드로이드 다크모드 폰트 명도 대비

- 원인을 추적해보니 **이 앱에 실제 다크 테마가 없다.** `tailwind.config.ts`는 `darkMode: 'class'`인데
  `.dark` 클래스를 붙이는 코드가 어디에도 없어 화면 곳곳의 `dark:` 유틸리티 클래스는 전부 죽은
  코드였다. 반면 `globals.css`의 `@media (prefers-color-scheme: dark)`는 실제로 반응하지만
  라이트 팔레트 토큰 중 3개(`--color-surface`, `--color-on-surface`, `--color-surface-container`)만
  덮어써서, 나머지(`on-surface-variant` 등 라이트 배경 전제로 고른 중간 톤 텍스트 색)가 이제
  거의 검정에 가까운 배경 위에 그대로 남아 대비가 무너진다.
- 근본 원인은 이 둘보다 **Android WebView의 알고리즘적 다크모드(force dark)** 로 판단했다 —
  앱이 다크 테마를 지원한다고 선언한 적이 없는데 시스템이 다크모드면 WebView가 임의로 색을
  반전·조정한다. `index.html`에 `<meta name="color-scheme" content="light">`를 추가해 이 문서가
  라이트 전용임을 선언(부수 효과로 `prefers-color-scheme` 미디어쿼리도 이 문서 안에서는 항상
  light로 평가되게 만든다)하고, `MainActivity.java`에서 `WebSettingsCompat.setAlgorithmicDarkeningAllowed(false)`
  (`androidx.webkit`, 기능 지원 여부 확인 후 호출)로 명시적으로 껐다.
- 진짜 다크 테마를 만드는 건 범위 밖으로 판단 — 지금은 "의도치 않게 자동 반전된 라이트 화면"을
  "항상 라이트로 보이는 화면"으로 되돌리는 것까지가 이번 수정이다.

### #534 — 배송내역 메인 화면 노출

- 라이더 홈(`rider/_authed/index.tsx`)은 이미 "배송 기록" 버튼이 메인 화면에 있었다 — 손댈 것 없음.
- 고객 홈(`customer/_authed/index.tsx`)에는 없었다 — "포인트 기록보기" 버튼 위에 같은 스타일의
  "배송내역"(`/customer/deliveries`) 버튼을 추가했다. 설정(`account/settings.tsx`) 안의 기존
  진입점은 그대로 남겼다(중복 노출, 제거 요청 없었음).

### #533 — 배송요청 생성 시 고객정보 자동입력

- **범위를 이름만으로 좁혔다** — 연락처(phoneNumber)는 회원 세션 응답(`CustomerSessionResponse`)에
  없었다. 백엔드는 이번에 필드를 추가했다(`AuthenticatedCustomer`·`CustomerSessionResponse`에
  `phoneNumber` 추가, `GET /api/customer/session`가 이제 함께 내려준다, 테스트 포함) — 하지만 프론트
  생성 타입에 반영하려면 Orval 재생성이 필요하고 이번 세션은 그걸 못 했다.
- "보내는 분"(sender)만 자동입력 대상이다 — 로그인한 고객 본인이기 때문이다. "받는 분"(recipient)은
  주문마다 다른 사람이라 대상이 아니다.
- `customer/_authed/deliveries/new.tsx`가 라우트 컨텍스트의 `session.customer.name`을
  `DeliveryForm`에 `defaultSenderName`으로 넘기고, 폼은 그 값으로 `sender.name`의 초기값을
  채운다(수정 가능, 읽기전용 아님).

### #535 — 라이더 위치 권한 필수화

- 기존 문제(CLAUDE.md에 이미 기록돼 있던 결함, #496): 네이티브 위치 서비스 시작이 권한 거부로
  실패해도 `console.error`로만 남고 라이더·고객 누구에게도 알리지 않았다 — 위치 없이 콜을 계속
  받을 수 있었다.
- `useLocationSender`에 `onLocationError` 콜백을 추가했다. `rider/_authed.tsx`(라이더 전용 화면
  전체를 감싸는 레이아웃, 위치 전송이 실제로 시작되는 지점)가 이 콜백으로 전체 화면을 막는
  안내 화면(`LocationPermissionRequiredScreen`)을 띄운다 — "권한 다시 확인하기"(재시도)와
  "운행 종료하고 나가기"(서버 상태를 UNAVAILABLE로 되돌리고 홈으로) 두 가지 탈출구를 준다.
- 네이티브 `RiderLocationPlugin.start()`는 원래도 `AVAILABLE` 전환 시점에 권한을 요청하지만(그 뒤
  BUSY가 아니라는 이유로 거부), 그 요청이 실패했을 때 아무도 못 보게 막혀 있던 것이 실제 결함이었다
  — 그래서 네이티브 코드는 건드리지 않고 프론트에서 실패를 반드시 처리하도록 만들었다.

### #532 — 고객 화면에 라이더 경로(OSRM) 표시, 직선거리 제거

**"직선거리"의 정체가 예상과 달랐다** — 배송 상세 화면의 텍스트 행("직선 거리: N km")이 아니라,
추적 지도(`TrackingMap.tsx`)가 픽업↔도착지 사이에 그리던 **2점짜리 카카오맵 Polyline**이었다.
텍스트 행은 이번에 손대지 않았다(요청과 다른 항목으로 판단).

- **백엔드(완성·테스트됨)**: `RoutingClient.findRoute`의 반환 타입을 `Optional<Duration>`에서
  `Optional<RouteEstimate>`(`duration` + `path`)로 다시 넓혔다 — `RoutingClient` 자바독이 이미
  "예전에 Route(duration/distance/path)였다가 Duration만 쓰는 소비자 때문에 좁혔다"고 기록해 둔
  바로 그 변경을 되돌린 것이다. `OsrmRoutingClient`는 `overview=false` 대신
  `overview=full&geometries=geojson`을 호출하고 GeoJSON `[경도,위도]`를 `Coordinate(위도,경도)`로
  뒤집어 파싱한다. `DeliveryEtaResponse`에 `path: List<RoutePointResponse>`(`{latitude, longitude}`
  이름 붙은 객체 배열)를 추가했다 — ETA와 같은 OSRM 호출 결과를 재사용하므로 새 엔드포인트나 추가
  OSRM 호출은 없다.
- **프론트(부분 완성)**: `TrackingMap.tsx`에서 픽업↔도착지 직선을 그리는 코드를 지웠다 — 이제
  `routePath` prop이 없으면 아무 선도 안 그린다(예전처럼 직선으로 대신하지 않는다). `routePath`를
  받으면 그 좌표들로 실제 경로를 그리도록 준비는 해 뒀지만, **아직 아무 화면도 이 prop을 채워
  보내지 않는다** — `useGetCustomerDeliveryEta`가 생성하는 타입에 `path`가 없어서다(Orval 재생성
  전이라 위 백엔드 필드가 타입에 안 잡힘). CLAUDE.md 경로 탐색·ETA 절에 재생성 후 남은 배선
  (`tracking.tsx`에서 `path`를 `TrackingMap`에 전달하는 한 줄)을 적어 뒀다.

## 사람이 고른 선택

이번 배치는 이슈 생성 단계에서 "이슈부터 7개 생성"을 사용자가 직접 골랐다(질문 1건, 옵션 3개 중
선택). 이후 "다 간단한거니까 연속으로 다해서 머지해"라는 지시로 계약 확정 질문 없이 바로
진행했다 — 개별 항목의 세부 선택(자동입력 대상을 이름으로 좁힌 것, OSRM 경로를 ETA 응답에 얹은
것 등)은 위 각 절에 근거와 함께 적었고, 별도로 사람에게 되묻지 않았다.

## 테스트

| 이슈 | 층 | 파일 | 검증한 것 |
|---|---|---|---|
| #536 | E2E | `CustomerDeliveryCreateE2ETest` | 받는 분 전화번호에 한글이 오면 400, 포인트 미차감 |
| #536 | 단위 | `-deliveryForm.test.ts` | 연락처 형식 오류 메시지 |
| #537 | E2E | `CustomerSignupE2ETest`, `RiderSignupE2ETest` | 8자 미만 비밀번호 400 |
| #537 | 단위 | 양쪽 `-signupForm.test.ts` | 8자 미만 필드 오류 |
| #533 | E2E | `CustomerSessionE2ETest` | 세션 응답에 phoneNumber 포함 |
| #532 | 단위 | `OsrmRoutingClientTest` | `overview=full&geometries=geojson` 쿼리, GeoJSON 좌표 뒤집기 |
| #532 | 단위 | `DeliveryRouteEstimatorTest`, `DeliveryEtaQueryServiceTest` | duration+path 배선 |
| #532 | 통합 | `DeliveryEtaQueryServiceIntegrationTest` | path가 응답에 실리는지(MySQL 필요, 이 세션에서 실행은 못 함 — 코드 리뷰로만 확인) |

실행 결과 (이 세션에서 실제로 돌린 것):

```text
cd frontend && pnpm typecheck && pnpm test && pnpm build
→ typecheck 통과, build 통과
→ test: 27/28 파일 통과, 224/225 케이스 통과
  (실패 1건은 kakaoMaps.test.ts — VITE_KAKAO_MAP_KEY가 없는 샌드박스 환경 문제, 이번 변경과 무관)

cd backend && ./gradlew compileJava compileTestJava
→ BUILD SUCCESSFUL

./gradlew test --tests (Docker 불필요한 순수 단위 테스트 48개 클래스 전체)
→ BUILD SUCCESSFUL, 전부 통과
```

### 검증하지 못한 것

- 백엔드 통합·E2E 테스트(MySQL/Redis 필요) 전체 회귀 — Docker 데몬 없음.
- Android 실제 컴파일·기기 확인 — Gradle 의존성 저장소 접근 차단.
- #532/#533의 프론트 완성 부분(경로 폴리라인 실제 렌더링, 연락처 자동입력) — Orval 재생성 필요.

## 새로 생긴 미결 사항

- **#532, #533의 프론트 배선이 남아 있다.** 백엔드는 완성·테스트됨. 로컬 백엔드를 띄우고
  `pnpm generate:api`를 돌린 뒤, `tracking.tsx`(#532)와 필요하면 `new.tsx`(#533, phoneNumber
  자동입력 추가 시)를 마저 연결해야 한다.
- **Android 변경(#531, #535 관련 위치 서비스 실패 흐름)은 실기기 확인이 필요하다.**
