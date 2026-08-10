# 프론트 API 연동 규칙

단계 4에서 읽는다. 범위가 `fullstack` 또는 `frontend`일 때만.

핵심 원칙 하나: **프론트는 API 클라이언트를 손으로 쓰지 않는다.** 백엔드 컨트롤러 → OpenAPI 문서 → Orval → React Query 훅이 자동으로 이어지고, 화면은 그 훅만 소비한다. 이 사슬을 끊는 순간(직접 axios 호출, 생성물 수정) 계약이 어긋난다.

## 1단계 — OpenAPI 스펙 갱신

Orval은 `orval.config.ts`의 `input.target`(= `http://localhost:8080/v3/api-docs`)에서 스펙을 읽으므로 **백엔드가 떠 있어야 한다.** local 프로파일은 Docker MySQL 8.4에 붙으므로 DB 컨테이너를 먼저 띄운다(`docs/05-local-dev.md`).

```bash
cd backend
docker compose up -d                                          # MySQL 먼저. healthy 확인 후 진행
./gradlew bootRun --args='--spring.profiles.active=local' &   # 백그라운드 기동
until curl -sf localhost:8080/api/health > /dev/null; do sleep 2; done
curl -sf localhost:8080/v3/api-docs | head -20                # 이번에 추가한 엔드포인트가 보이는지
```

새 엔드포인트가 문서에 안 보이면 되돌아가 컨트롤러 어노테이션을 고친다. 확인하지 않고 생성하면 `unknown` 타입이 프론트로 흘러간다.

## 2단계 — Orval 재생성

```bash
cd frontend
pnpm install            # node_modules 가 없을 때만
pnpm generate:api
```

생성 결과는 `src/api/generated/` 아래 **태그별 분리 파일**로 떨어진다(`mode: 'tags-split'`). `@Tag(name = "customer-delivery")`면 `customer-delivery/customer-delivery.ts`가 생기고, 공통 타입은 `turkeyQuickDeliveryAPI.schemas.ts`에 모인다.

실행 중 나오는 아래 경고는 무해하다(mutator를 orval이 esbuild로 변환하며 나오는 것, 생성물에 영향 없음). 고치려고 `src/lib/axios.ts`를 건드리지 않는다.

```
▲ [WARNING] "import.meta" is not available with the "cjs" output format [empty-import-meta]
    src/lib/axios.ts:9:11
```

생성 후 확인:
- 새 훅이 생겼는가(`useCreateDelivery`, `useGetDeliveries`).
- 요청/응답 타입이 `unknown`/`any`가 아닌가.
- 훅 이름이 알아볼 만한가 — 아니면 `@Operation(operationId = "...")`으로 백엔드에서 이름을 정한다.

`src/api/generated/`는 **자동 생성물, 절대 손으로 고치지 않는다.** 결과가 마음에 안 들면 고칠 곳은 언제나 백엔드 어노테이션이다. 생성 파일은 커밋에 포함한다(프론트만 받는 사람이 백엔드 없이 쓸 수 있게). 기동해 둔 백엔드는 작업이 끝나면 정리한다.

## 3단계 — 화면 연결

생성된 훅을 라우트나 `-components/`에서 소비한다.

```tsx
import { useCreateDelivery } from '@/api/generated/customer-delivery/customer-delivery'

export function DeliveryForm() {
  const { mutate, isPending, error } = useCreateDelivery()
  // ...
}
```

지켜야 할 것:

- **라우트 구조를 새로 만들지 않는다.** 화면은 이미 정적 마크업으로 존재한다(`src/routes/customer/...`, `.../rider/...`). 이번 작업은 대개 그 화면에 훅을 붙이는 일이다. 화면이 없을 때만 새로 만들고, 라우트 규칙은 `CLAUDE.md` 「프론트엔드 아키텍처」를 따른다.
- `routeTree.gen.ts`는 자동 생성물이다. 손으로 고치지 않는다.
- 라우팅 대상이 아닌 컴포넌트는 `-components/` 아래에 `createFileRoute` 없이 `export function`으로.
- 로딩·에러 상태를 반드시 처리하고, 에러 표시에 `role="alert"`를 붙인다(접근성).
- 세션 쿠키 인증이라 `withCredentials: true`가 유지돼야 한다(`src/lib/axios.ts`의 `customInstance`가 처리). 401 처리 등 공통 동작은 화면이 아니라 `axiosInstance` 인터셉터에 넣는다.
- 색상·간격은 `src/styles/globals.css` 토큰을 쓰고, 하드코딩이 반복되면 토큰으로 승격한다.
- `frontend/CLAUDE.md`에 shadcn/ui 우선 규칙이 있지만 `src/components/ui/`는 아직 없다. 없는 상태에서 shadcn 도입은 새 의존성 추가이므로 임의로 하지 말고 필요하면 단계 2에서 확인받는다.

## SSE는 Orval 대상이 아니다

실시간 라이더 위치는 REST가 아니라 SSE다. Orval이 다루지 않으니 훅을 찾지 말고 `src/shared/hooks/useTrackingStream.ts`(고객 구독) / `useLocationSender.ts`(라이더 전송)를 쓴다. 연결·재연결·종료 처리는 그 훅 안에 모은다. 화면마다 `EventSource`를 새로 만들지 않는다.

## 검증

```bash
cd frontend
pnpm typecheck        # tsc -b --noEmit
pnpm build            # 타입 + 번들까지
```

타입 에러가 `src/api/generated/`에서 나면 원인은 백엔드 스펙이다. 생성물을 고치지 말고 백엔드로 돌아간다.
