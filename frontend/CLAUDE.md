## UI 컴포넌트
- 인터랙티브 UI는 우선 src/components/ui/ 의 shadcn/ui 조합을 사용
- raw <button>, <input> 은 특별한 이유가 없으면 재구현하지 않음

## 디자인 토큰
- src/styles/globals.css 의 @theme 토큰을 우선 사용
- 색상 하드코딩은 가급적 피하고 반복되면 토큰으로 승격

## API
- 기본은 src/api/generated/ 의 Orval 훅 사용
- 예외가 있으면 lib/axios.ts 또는 mutator 레이어에서 처리

### Orval 셋업(확정)
- `orval.config.ts`: `mode: 'tags-split'`(OpenAPI tag=도메인별 파일 분리, 백엔드 패키지 구조와 대응), `client: 'react-query'`, `httpClient: 'axios'`.
- mutator 는 `src/lib/axios.ts` 의 **`customInstance`**(axios 인스턴스가 아니라 함수). 응답 body(`data`)를 언랩해 반환하고 `.cancel()`을 붙여 React Query 취소를 지원한다.
- 에러/바디 타입: `ErrorType<E>` = `AxiosError<E>`, `BodyType<B>` (`src/lib/axios.ts` export).
- 공통 처리(401 → 로그인 리다이렉트 등)는 `axiosInstance` 인터셉터에 등록한다.
- 세션 인증이므로 `withCredentials: true` 유지(쿠키 기반 서버 세션).
- `input.target` 은 `http://localhost:8080/v3/api-docs`(springdoc) 이다. 생성 전에 백엔드를 local 프로파일로 띄운 뒤 `pnpm generate:api` 를 실행한다.
- `src/api/generated/` 는 **자동 생성물, 수정 금지**. 화면은 이 훅만 소비한다.
- 실시간 위치는 REST 가 아니므로 Orval 대상 아님 → `shared/hooks/useTrackingStream`(SSE)로 분리 유지.

### 재생성 절차(#194)

백엔드 API 가 추가·변경되면 아래를 그대로 실행한다. 생성물은 커밋에 포함한다 —
프론트만 받는 사람이 백엔드를 띄우지 않고도 타입·훅을 쓸 수 있어야 한다.

```bash
# 1) 로컬 DB·Redis (docs/05-local-dev.md)
cd backend && docker compose up -d
docker compose ps            # STATUS 가 healthy 가 될 때까지 대기

# 2) 백엔드 기동 (local 프로파일)
./gradlew bootRun --args='--spring.profiles.active=local'

# 3) 다른 셸에서 기동·스펙 노출 확인
until curl -sf localhost:8080/api/health > /dev/null; do sleep 2; done
curl -sf localhost:8080/v3/api-docs > /dev/null && echo spec ok

# 4) 재생성 + 검증
cd frontend && pnpm generate:api
pnpm typecheck && pnpm build
```

`import.meta is not available with the "cjs" output format` 경고는 orval 이 mutator(`src/lib/axios.ts`)를
esbuild 로 한 번 변환하면서 나오는 것이고 생성물에는 영향이 없다. **이 경고를 없애려고 `src/lib/axios.ts` 를 수정하지 않는다.**

생성 후 확인할 것:

- 새 훅이 생겼는지, 이름이 **어떤 액터의 어떤 행위인지 읽히는지**(`useRiderLogin`, `useGetCustomerSession`)
- 요청/응답 타입에 `unknown`/`any` 가 없는지. 단 `ErrorType<unknown>`·`TContext = unknown` 은
  Orval 제네릭 기본값이라 정상이다. 실제 확인 대상은 `*.schemas.ts` 의 스키마 타입이다.

**훅 이름은 백엔드 `operationId` 에서 온다.** 마음에 안 들면 생성물이 아니라
컨트롤러의 `@Operation(operationId = "...")` 을 고친다. operationId 를 생략하면 springdoc 이
메서드명을 쓰고, 두 컨트롤러가 같은 메서드명(`login`/`signup`/`session`)을 쓰면 나중 것에 `_1` 을 붙인다
→ `useLogin` / `useLogin1` 처럼 액터를 구분할 수 없는 이름이 되고, 어느 쪽이 `_1` 인지는 스캔 순서에 달려 있다.
이 회귀는 백엔드 `OpenApiOperationIdE2ETest` 가 잡는다.

## 디자인 시안(HTML) → TSX 변환 규칙
`~/Downloads` 등의 Stitch/디자인 시안 HTML 을 라우트/컴포넌트로 옮길 때 아래를 지킨다.

- **파일 형태**
  - 라우트: `export const Route = createFileRoute('<경로>')({ component: X })` + `function X() { return (<JSX/>) }`.
  - `-components/` 하위(라우팅 제외): `createFileRoute` 없이 일반 `export function X()`.
- **제거 대상**: `<!DOCTYPE>`, `<html>`, `<head>`, 폰트 `<link>`, `<script src=cdn.tailwindcss>`, 인라인 `<script id="tailwind-config">`, 폰트/스크롤바용 `<style>`. `<body>` 안쪽 마크업만 컴포넌트 JSX 로 가져온다. 디자인 토큰은 `tailwind.config.ts` 로 이미 승격됨.
- **Tailwind 클래스는 원본 그대로 유지**(`class` → `className`). 임의값 클래스(`text-[#2D7FF9]`, `bg-[url(...)]`)도 유지하되, 원격 이미지 URL(`bg-[url(...)]`)은 제거하고 `bg-surface-container-high` + TODO 주석으로 대체.
- **HTML→JSX 변환**: `class→className`, `for→htmlFor`, void 태그 self-close, `style="a:b"` → `style={{ a: 'b' }}`(camelCase), `<!-- -->` → `{/* */}`, 인라인 SVG 는 `viewbox→viewBox`/`stroke-width→strokeWidth`/`stroke-linecap→strokeLinecap` 등.
- **입력값**: 정적 시안 값은 `value→defaultValue`, `checked→defaultChecked`, `autofocus→autoFocus`(제어 컴포넌트 경고 방지). 실제 폼은 이후 상태/훅과 연결.
- **아이콘**: 시안의 Material Symbols(`<span className="material-symbols-outlined">arrow_back</span>`)는 그대로 유지(전역 폰트 로드). 신규 작성분에서 lucide-react 를 쓸 경우 혼용하지 말고 화면 단위로 통일.
- **이미지**: 외부/플레이스홀더 이미지(googleusercontent, placehold.co)는 커밋하지 않는다 → 동일 sizing 의 `bg-surface-container-high` placeholder div + `{/* TODO: 실제 이미지 연결 */}`.
- **범위**: 변환 시점엔 프레젠테이션(정적)만. API 연동은 위 Orval 훅으로 별도 단계에서 붙인다. 상태 전이/배차/포인트 등 정합성 로직은 화면단에서 추측 구현하지 않는다.
- 시안 전용 헬퍼 클래스(`.text-blurred` 개인정보 블러, `.dotted-line` 주소 연결선)는 `src/styles/globals.css` 에 정의되어 있으니 재사용.

## 레이아웃
- 기본은 flex / grid 사용
- position: absolute 는 오버레이/장식 등 이유가 있을 때만 사용
- 간격 조정용 빈 <div> 금지 — gap / padding / margin 사용

## 접근성
- 페이지 루트: <main aria-label="...">
- 에러/상태 변화: role="alert" 또는 aria-live