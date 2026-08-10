## UI 컴포넌트
- 인터랙티브 UI는 `src/components/ui/`의 shadcn/ui 조합을 우선 사용. raw `<button>`·`<input>`은 특별한 이유 없이 재구현하지 않음.

## 디자인 토큰
- `src/styles/globals.css`의 `@theme` 토큰을 우선 사용. 색상 하드코딩은 피하고 반복되면 토큰으로 승격.

## API
- 기본은 `src/api/generated/`의 Orval 훅 사용. 예외는 `lib/axios.ts` 또는 mutator 레이어에서 처리.

### Orval 셋업(확정)
- `orval.config.ts`: `mode: 'tags-split'`(OpenAPI tag=도메인별 파일 분리, 백엔드 패키지 구조와 대응), `client: 'react-query'`, `httpClient: 'axios'`.
- mutator는 `src/lib/axios.ts`의 **`customInstance`**(axios 인스턴스가 아니라 함수). 응답 body(`data`)를 언랩해 반환하고 `.cancel()`로 React Query 취소를 지원.
- 에러/바디 타입: `ErrorType<E>` = `AxiosError<E>`, `BodyType<B>`(`src/lib/axios.ts` export).
- 공통 처리(401 → 로그인 리다이렉트 등)는 `axiosInstance` 인터셉터에 등록.
- 세션 인증이므로 `withCredentials: true` 유지(쿠키 기반 서버 세션).
- `input.target`은 `http://localhost:8080/v3/api-docs`(springdoc). 생성 전 백엔드를 local 프로파일로 띄운 뒤 `pnpm generate:api` 실행.
- `src/api/generated/`는 **자동 생성물, 수정 금지**. 화면은 이 훅만 소비.
- 실시간 위치는 REST가 아니므로 Orval 대상 아님 → `shared/hooks/useTrackingStream`(SSE)로 분리 유지.

### 재생성 절차(#194)

백엔드 API가 추가·변경되면 아래를 실행한다. 생성물은 커밋에 포함한다(프론트만 받는 사람이 백엔드 없이 타입·훅을 쓸 수 있어야 함).

```bash
# 1) 로컬 DB·Redis (docs/05-local-dev.md)
cd backend && docker compose up -d
docker compose ps            # STATUS healthy 대기

# 2) 백엔드 기동 (local 프로파일)
./gradlew bootRun --args='--spring.profiles.active=local'

# 3) 다른 셸에서 기동·스펙 노출 확인
until curl -sf localhost:8080/api/health > /dev/null; do sleep 2; done
curl -sf localhost:8080/v3/api-docs > /dev/null && echo spec ok

# 4) 재생성 + 검증
cd frontend && pnpm generate:api
pnpm typecheck && pnpm build
```

`import.meta is not available with the "cjs" output format` 경고는 orval이 mutator(`src/lib/axios.ts`)를 esbuild로 변환하며 나오는 것이고 생성물에는 영향이 없다. **이 경고를 없애려고 `src/lib/axios.ts`를 수정하지 않는다.**

생성 후 확인:
- 새 훅이 생겼는지, 이름이 **어떤 액터의 어떤 행위인지 읽히는지**(`useRiderLogin`, `useGetCustomerSession`).
- 요청/응답 타입에 `unknown`/`any`가 없는지. 단 `ErrorType<unknown>`·`TContext = unknown`은 Orval 제네릭 기본값이라 정상 — 실제 확인 대상은 `*.schemas.ts`의 스키마 타입.

**훅 이름은 백엔드 `operationId`에서 온다.** 마음에 안 들면 생성물이 아니라 컨트롤러의 `@Operation(operationId = "...")`을 고친다. 생략하면 springdoc이 메서드명을 쓰고, 두 컨트롤러가 같은 메서드명(`login`/`signup`/`session`)을 쓰면 나중 것에 `_1`을 붙여(`useLogin`/`useLogin1`) 액터를 구분할 수 없게 된다. 이 회귀는 백엔드 `OpenApiOperationIdE2ETest`가 잡는다.

## 인증 가드 (#195)

라우트 가드는 `src/shared/auth/`에 모여 있다. 화면은 401을 개별 처리하지 않는다.

| 파일 | 역할 |
|---|---|
| `shared/auth/guard.ts` | 순수 판정 로직(어디로 보낼지). 라우터·네트워크 의존 없음 → 단위 테스트 대상 |
| `shared/auth/session.ts` | 저장된 역할 힌트에 따른 단일 세션 조회(`ensureSessionInfo`) |
| `shared/auth/sessionRole.ts` | 로그인 성공 시 역할 힌트 저장·조회·정리(`#288` 임시 완화) |
| `shared/auth/redirectSearch.ts` | `?redirect=` 검증(같은 출처 절대 경로만) |
| `shared/auth/SessionErrorScreen.tsx` | 세션 확인이 **실패**했을 때 화면(만료와 구분) |

가드가 걸린 곳:

| 라우트 | 가드 | 비로그인 시 |
|---|---|---|
| `customer/_authed/**` | 인증 + 고객 역할 | `/customer/login?redirect=…` |
| `rider/_authed/**` | 인증 + 라이더 역할 + 운행 상태 정합성 | `/rider/login?redirect=…` |
| `account/**` (`account/route.tsx`) | 인증만(역할 무관, 공용 화면) | `/?redirect=…` |
| `auth/**` (`auth/route.tsx`) | **비인증**(로그인 상태면 역할 홈으로) | 통과 |

지켜야 할 것:

- **보호가 필요한 새 화면은 `customer/_authed/` 또는 `rider/_authed/` 하위에 만든다.** `_authed`는 경로 없는 레이아웃이라 URL은 안 바뀐다. 밖에 만들면 가드 없이 열린다(백엔드 인터셉터 등록 누락과 같은 종류의 실수).
- 로그인 성공 시 역할 힌트(`CUSTOMER`/`RIDER`)를 `localStorage`에 저장하고, 이후 **해당 역할의 세션 API 하나만 조회**한다(#288). 힌트가 없으면 세션 API를 호출하지 않고 비로그인으로 판정. 이 값은 조회 대상 선택용일 뿐 인증·인가 근거가 아니며, 401·로그아웃 시 세션 캐시와 함께 제거한다. 역할 무관 `GET /api/session`이 생기면 힌트를 제거하고 서버 응답의 역할을 정본으로 쓴다.
- **401이 아닌 실패(네트워크·5xx)를 만료로 처리하지 않는다.** 그러면 서버가 잠깐 흔들릴 때 전원이 로그아웃된다.
- 401 공통 처리는 `axiosInstance` 인터셉터 + `main.tsx` 배선이다. 단 세션 확인·로그인 경로는 제외(전자는 가드의 정상 판정 신호, 후자는 자격 증명 오류라 폼에 표시).
- 라이더 운행 상태 강제는 홈·콜목록·진행배송 **3개 화면끼리만** 적용. 이력·정산까지 강제하면 BUSY 라이더가 그 화면을 못 연다.
- `baseURL`은 빈 문자열이다. 생성된 URL이 이미 `/api/...`라 여기에 `/api`를 넣으면 `/api/api/...`가 된다.

## 테스트

```bash
pnpm test        # vitest run — src/**/*.test.ts
pnpm typecheck
pnpm build
```

가드처럼 분기가 많은 로직은 **순수 함수로 떼어내 테스트**한다(라우터를 띄우지 않음). 네트워크가 개입하는 부분은 생성된 API 모듈을 `vi.mock`으로 대체 — 실제 서버에 붙는 테스트는 두지 않는다.

## 디자인 시안(HTML) → TSX 변환 규칙

`~/Downloads` 등의 Stitch/디자인 시안 HTML을 라우트/컴포넌트로 옮길 때:

- **파일 형태**: 라우트는 `export const Route = createFileRoute('<경로>')({ component: X })` + `function X() { return (<JSX/>) }`. `-components/` 하위(라우팅 제외)는 `createFileRoute` 없이 일반 `export function X()`.
- **제거 대상**: `<!DOCTYPE>`, `<html>`, `<head>`, 폰트 `<link>`, `<script src=cdn.tailwindcss>`, 인라인 `<script id="tailwind-config">`, 폰트/스크롤바용 `<style>`. `<body>` 안쪽 마크업만 JSX로 가져온다(디자인 토큰은 `tailwind.config.ts`로 이미 승격됨).
- **Tailwind 클래스는 원본 그대로 유지**(`class`→`className`). 임의값 클래스(`text-[#2D7FF9]`)도 유지하되, 원격 이미지 URL(`bg-[url(...)]`)은 제거하고 `bg-surface-container-high` + TODO 주석으로 대체.
- **HTML→JSX 변환**: `class→className`, `for→htmlFor`, void 태그 self-close, `style="a:b"`→`style={{ a: 'b' }}`(camelCase), `<!-- -->`→`{/* */}`, 인라인 SVG는 `viewbox→viewBox`/`stroke-width→strokeWidth`/`stroke-linecap→strokeLinecap` 등.
- **입력값**: 정적 시안 값은 `value→defaultValue`, `checked→defaultChecked`, `autofocus→autoFocus`(제어 컴포넌트 경고 방지). 실제 폼은 이후 상태/훅과 연결.
- **아이콘**: 시안의 Material Symbols(`<span className="material-symbols-outlined">arrow_back</span>`)는 그대로 유지(전역 폰트 로드). 신규 작성에 lucide-react를 쓰면 혼용하지 말고 화면 단위로 통일.
- **이미지**: 외부/플레이스홀더 이미지(googleusercontent, placehold.co)는 커밋하지 않고 동일 sizing의 `bg-surface-container-high` placeholder div + `{/* TODO: 실제 이미지 연결 */}`로 대체.
- **범위**: 변환 시점엔 프레젠테이션(정적)만. API 연동은 Orval 훅으로 별도 단계에서. 상태 전이/배차/포인트 등 정합성 로직은 화면단에서 추측 구현하지 않는다.
- 시안 전용 헬퍼 클래스(`.text-blurred`, `.dotted-line`)는 `src/styles/globals.css`에 정의돼 있으니 재사용.

## 레이아웃
- 기본은 flex/grid. `position: absolute`는 오버레이/장식 등 이유가 있을 때만. 간격 조정용 빈 `<div>` 금지 — gap/padding/margin 사용.

## 접근성
- 페이지 루트: `<main aria-label="...">`. 에러/상태 변화: `role="alert"` 또는 `aria-live`.
