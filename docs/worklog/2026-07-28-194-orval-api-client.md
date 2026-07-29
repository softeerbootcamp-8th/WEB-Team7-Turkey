# Orval API 클라이언트 최초 생성 작업 기록

- 이슈: [#194](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/194)
- 브랜치: `feature/194-orval-api-client`
- 범위: frontend (+ 백엔드 springdoc 애노테이션 보강)
- 작성일: 2026-07-28

## 무엇을 만들었나

`src/api/generated/` 가 `.gitkeep` 만 있는 빈 디렉터리였다. 로컬 백엔드(local 프로파일)를 띄워
`/v3/api-docs` 스펙을 확인하고 `pnpm generate:api` 로 Orval React Query 클라이언트를 최초 생성해 커밋했다.

생성 도중 훅 이름이 액터를 구분하지 못하는 문제가 드러나(`useLogin` / `useLogin1`) 사람 확인을 거쳐
백엔드 컨트롤러 9개에 `@Operation(operationId = ...)` 을 명시한 뒤 재생성했다. 그리고 이 문제가
다시 조용히 생기지 않도록 백엔드에 operationId 계약 테스트를 추가했다.

태그 11개 / 오퍼레이션 17개 → 태그별 파일 11개 + 공용 타입 `turkeyQuickDeliveryAPI.schemas.ts`(스키마 타입 68개),
훅 17개가 생성됐다.

### API

새로 만든 엔드포인트는 없다. 기존 17개 오퍼레이션의 `operationId` 만 명시했다.

| 태그 | 오퍼레이션 | operationId | 생성된 훅 |
|---|---|---|---|
| `customer-signup` | POST `/api/customer/signup` | `customerSignup` | `useCustomerSignup` |
| `customer-login` | POST `/api/customer/login` | `customerLogin` | `useCustomerLogin` |
| `customer-logout` | POST `/api/customer/logout` | `customerLogout` | `useCustomerLogout` |
| `customer-session` | GET `/api/customer/session` | `getCustomerSession` | `useGetCustomerSession` |
| `rider-signup` | POST `/api/rider/signup` | `riderSignup` | `useRiderSignup` |
| `rider-login` | POST `/api/rider/login` | `riderLogin` | `useRiderLogin` |
| `rider-session` | GET `/api/rider/session` | `getRiderSession` | `useGetRiderSession` |
| `login-id` | GET `/api/login-ids/availability` | `checkLoginIdAvailability` | `useCheckLoginIdAvailability` |
| `phone-verification` | POST `/api/phone-verifications` | `requestPhoneVerification` | `useRequestPhoneVerification` |
| `phone-verification` | POST `/api/phone-verifications/confirm` | `confirmPhoneVerification` | `useConfirmPhoneVerification` |
| `customer-delivery` | 6건 | 변경 없음 | `useGetDeliveries` `useCreateDelivery` `useQuoteFare` `useGetDelivery` `useCancelDelivery` `useGetDeliveryTracking` |
| `health-controller` | GET `/api/health` | `health` (변경 없음) | `useHealth` |

`customer-delivery` 와 `health` 는 원래 이름이 충돌 없이 읽혀서 손대지 않았다.

### 화면

해당 없음. 이번 이슈는 훅 생성까지이고, 화면 연결은 후속 화면 연동 이슈들이 한다.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 액터 구분이 안 되는 훅 이름을 언제 고칠 것인가

- **물었던 것**: springdoc 이 고객/라이더 컨트롤러의 동명 메서드에 `_1` 접미사를 붙여
  `useLogin`(라이더) / `useLogin1`(고객), `useSignup` / `useSignup1`, `useSession` / `useSession1` 로
  생성됐다. 이슈 「예외 흐름」은 "생성물을 손대지 말고 필요하면 별도 백엔드 이슈로 분리"를 지시한다.
- **선택지**:
  - (A) 지금 백엔드 `operationId` 를 보강한 뒤 재생성 — #194 범위(frontend)를 백엔드로 조금 넘기지만,
    downstream 화면 이슈들이 나쁜 이름을 import 하는 일과 나중 rename 시의 import churn 을 원천 차단
  - (B) 현재 이름으로 커밋 + 별도 백엔드 이슈 등록 — 이슈 범위를 엄격히 지키지만
    모든 화면 연동 이슈가 `useLogin1` 로 먼저 붙고 나중에 전부 고쳐야 함
  - (C) 현재 이름 그대로 확정, 후속 조치 없음 — 가장 빠르지만 가독성 문제와 스캔 순서 의존이 영구화
- **고른 것**: (A)
- **근거**: 사람 확인 — "지금 백엔드 operationId 보강".
- **영향**: 이후 화면 연동 이슈는 `useCustomerLogin` / `useRiderLogin` 처럼 액터가 이름에 박힌 훅을
  import한다. 새 컨트롤러를 추가할 때 `@Operation(operationId = ...)` 명시가 **사실상 필수**가 됐고,
  빠뜨리면 `OpenApiOperationIdE2ETest` 가 실패한다.

## 스스로 판단한 것

- **`_1` 접미사 문제를 "가독성"이 아니라 "회귀 위험"으로 본 것**: `_1` 배정 순서는 컨트롤러 스캔
  순서에 달려 있다. 즉 컨트롤러를 하나 추가·이동하기만 해도 `useLogin` 이 가리키는 액터가 바뀔 수
  있다. 로그인/회원가입은 요청 본문 타입이 달라 타입 에러로 드러나겠지만, 세션 조회(GET, 본문 없음)는
  응답 필드를 안 읽으면 조용히 다른 액터를 찌른다. 그래서 문서 코멘트가 아니라 테스트로 막았다.

- **회귀 방어를 프론트가 아니라 백엔드 테스트로 둔 것**: 프론트에는 테스트 러너가 없다(vitest 미도입).
  러너 추가는 새 의존성이라 임의로 할 수 없고, 애초에 깨지는 원인은 백엔드 애노테이션이므로
  원인 쪽에서 잡는 게 맞다. `OpenApiOperationIdE2ETest` 는 실제로 스펙을 뜨워 읽는다
  (① operationId 존재 ② 중복 없음 ③ `_숫자` 접미사 없음 ④ 인증 오퍼레이션 7개 이름 고정).

- **operationId 네이밍을 `customerLogin` / `getCustomerSession` 형태로 한 것**: 기존
  `customer-delivery` 가 `getDeliveries` · `createDelivery` 처럼 동사 우선 camelCase 라서 조회는
  `get-` 접두를 맞췄고, 로그인/가입은 훅 이름(`useCustomerLogin`)이 더 자연스럽게 읽히는 쪽을 택했다.

- **`ApiResponseVoidData = { [key: string]: unknown }` 을 남겨둔 것**: `ApiResponse<Void>` 의 `data`
  에 스키마가 없어 springdoc 이 빈 object 로 내보낸 결과다. 의미상 "데이터 없음"이라 소비 측에 해가
  없고, 고치려면 `ApiResponse<Void>` 직렬화 형태를 건드려야 해서 이번 범위에서 제외했다.

- **브랜치를 `origin/dev` 에서 딴 것**: 기능 통합 브랜치가 `dev` 이고(`main` 은 뒤처져 있다),
  작업 시작 시점의 `docs/frontend-api-map` 브랜치에 있던 미머지 문서 커밋을 이 PR 에 섞지 않기 위해서다.

## 일부러 하지 않은 것

- **`src/api/generated/` 손수정**: 자동 생성물이므로 금지. 고칠 곳은 항상 백엔드 애노테이션이다.
- **`src/lib/axios.ts` 의 `import.meta` 경고 제거**: 이슈가 명시적으로 금지. orval 이 mutator 를
  esbuild(cjs)로 변환하며 나오는 경고이고 생성물에는 영향이 없다.
- **화면-훅 연결**: 후속 화면 연동 이슈 범위.
- **에러 응답 본문 타입 지정(`ErrorType<unknown>` → 구체 타입)**: 스펙에 에러 스키마가 선언돼 있지
  않아 지금은 `unknown` 이다. 컨트롤러에 `@ApiResponse(content = ...)` 로 공통 에러 스키마를 붙이고
  orval `override.errorType` 을 지정해야 하는데, 전 컨트롤러에 걸치는 별개 작업이다 — 아래 미결로 남김.
- **프론트 테스트 러너(vitest) 도입**: 새 의존성이라 사람 확인 없이 하지 않았다. 이슈 완료 조건의
  "테스트 코드 작성"은 백엔드 operationId 계약 테스트로 충족했다.
- **`health-controller` 태그 정리**: 이슈 표에는 없지만 스펙에 노출돼 `useHealth` 훅이 생성된다
  (그래서 태그 11개 / 오퍼레이션 17개가 맞는다). 프론트에서 쓸 일은 없지만 제거는 범위 밖이다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | 해당 없음 | 새 도메인 규칙·계산 없음 |
| 통합 | 해당 없음 | DB 정합성이 개입하지 않음 |
| E2E | `backend/.../common/openapi/OpenApiOperationIdE2ETest.java` | 실제 `/v3/api-docs` 응답에서 operationId 존재·중복 없음·`_숫자` 접미사 없음·인증 오퍼레이션 7개 이름 고정 |

실행 결과:

```text
./gradlew test --tests '*OpenApiOperationIdE2ETest'
  → tests=4 failures=0 errors=0 skipped=0

./gradlew test  (전체)
  → BUILD SUCCESSFUL

cd frontend && pnpm typecheck   → tsc -b --noEmit, 출력 없음(통과)
cd frontend && pnpm build       → ✓ built in 1.40s
```

새 테스트가 헛돌지 않는지 확인하려고 `RiderLoginController` 의 `operationId` 를 일시 제거해
`고객_라이더_공통_행위의_operationId는_액터를_구분한다()` 가 FAILED 되는 것을 보고 원복했다
(`4 tests completed, 1 failed`).

### 검증하지 못한 것

- **실제 `_1` 충돌 상황의 재현**: operationId 하나만 지우면 충돌이 안 생겨 `_1` 이 붙지 않는다
  (`login` 으로 생성됨). `_숫자` 접미사 검사 자체는 두 컨트롤러가 같은 메서드명을 쓸 때만 발동하므로,
  그 케이스는 애노테이션 보강 전 실제 스펙(`login_1` / `signup_1` / `session_1`)에서 관찰한 것으로 갈음했다.
- **생성된 훅의 런타임 동작**: 화면에 붙이지 않았으므로 타입 컴파일까지만 확인했다. 실제 요청·쿠키
  전송·401 처리는 첫 화면 연동 이슈에서 드러난다.

## 새로 생긴 미결 사항

- 에러 응답 본문 타입이 스펙에 없어 모든 훅의 `error` 가 `AxiosError<unknown>` 이다. 공통 에러 스키마를
  springdoc 에 노출하고 orval `override.errorType` 을 지정할지, 화면에서 `unknown` 을 좁혀 쓸지 미결.
- 새 컨트롤러 추가 시 `@Operation(operationId = ...)` 명시가 필수가 됐다. 이 규칙을 리뷰 체크리스트나
  PR 템플릿에 넣을지 미결(현재는 `OpenApiOperationIdE2ETest` 실패로만 알게 된다).
