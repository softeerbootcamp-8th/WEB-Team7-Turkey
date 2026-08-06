# 배송 상태 전이 SSE 실시간 전달 — 프론트엔드 작업 기록

- 이슈: [#399](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/399)
- 브랜치: `feature/399-status-sse-tracking-frontend`
- 범위: frontend
- 작성일: 2026-08-06

## 무엇을 만들었나

기존 위치 전용 SSE 훅(`useTrackingStream`)이 `type` 판별 필드로 LOCATION/STATUS 프레임을
구분하도록 확장했다. STATUS 프레임은 위치 파싱을 건너뛰고 `statusChangedAt`(신호값, 렌더링에는
안 쓴다) 만 갱신한다. 추적 화면(`tracking.tsx`)은 그 신호가 바뀔 때 상세 조회(`useGetDelivery`)를
`refetch()`해 상태·단계 표시를 REST 기준으로 다시 그린다.

### 화면

- `routes/customer/_authed/deliveries/$deliveryId/tracking.tsx` — `statusChangedAt` 변화를
  감지하는 `useEffect` 추가, `detailQuery.refetch()` 호출.
- `shared/hooks/useTrackingStream.ts` — `type` 판별(`parseFrameType`), `statusChangedAt` 상태 추가.

### 스키마 변경

해당 없음(SSE는 Orval 대상 아님).

## 사람이 고른 선택

### 1. #398(백엔드) 미완료 상태에서 진행 여부

- **물었던 것**: #399는 `Blocked by #398`인데 #398이 아직 OPEN이다. #398부터 먼저 할지, #399를
  프론트 계약만으로 선구현할지.
- **선택지**:
  - (A) #398 먼저 구현 — 로컬 수동 확인까지 검증 가능 / 순서상 안전하지만 이번 요청(#399)과 다른 이슈
  - (B) #399만 선구현 — 계약(이슈 본문의 필드 이름)만으로 코드는 짤 수 있음 / 수동 확인·통합 검증은
    #398 완료 전까지 불가능, 필드명이 실제 구현과 다르면 나중에 수정 필요
- **고른 것**: (B)
- **근거**: 사용자가 "#399만 벤 버리치에 별도 커밋"으로 명시적으로 선택.
- **영향**: 이슈 본문의 예시 표기(`LOCATION`/`STATUS`, 대문자)는 필드명 설명용이었고, 실제로는
  작업 중이던 `feature/398-status-sse-publish` 브랜치에 **커밋되지 않은 채로** #398 백엔드 구현이
  이미 존재했다. 그 워킹트리를 읽어 실제 계약(`type: "location"` / `"status"`, 소문자)을 확인하고
  거기에 맞춰 코드를 작성했다 — 우연히 (B)를 선택했음에도 실제 계약과 어긋나지 않게 됐다.

### 2. 브랜치·커밋 분리 방식

- **물었던 것**: `feature/398-status-sse-publish` 브랜치에 #398 백엔드가 커밋 안 된 채로 있다.
  #399 프론트 변경을 어디에 커밋할지.
- **선택지**:
  - (A) 같은 브랜치에 함께 커밋 — #397 하나의 기능으로 묶임 / #398이 아직 사람 확인·리뷰 전인
    남의 작업물과 내 커밋이 섞임
  - (B) #399만 별도 브랜치에 분리 커밋 — #398 작업자의 워킹트리를 건드리지 않음 / 나중에 #398과
    #399를 합칠 때 별도 PR 조율 필요
- **고른 것**: (B)
- **근거**: 사용자 선택.
- **영향**: `dev`에서 새로 브랜치(`feature/399-status-sse-tracking-frontend`)를 파서 프론트 변경만
  커밋했다. 이 과정에서 `dev`의 `tracking.tsx`가 `feature/398` 브랜치 시점과 이미 달라져 있는 걸
  발견했다(별도 리팩터로 `useGetDeliveryTracking` 제거, `useGetDelivery` 단일 조회로 통합됨) — 병합
  충돌을 `dev` 기준 최신 구조에 맞춰 수동으로 재적용했다.

## 스스로 판단한 것

- **`statusChangedAt`을 `Date.now()` 값으로 채움**: 값 자체는 안 쓰고 effect 트리거로만 쓰므로
  타임스탬프든 카운터든 상관없다. 기존 코드에 카운터 패턴이 없어 더 직관적인 타임스탬프를 택함.
- **`type` 필드가 없는 프레임은 LOCATION으로 간주**: 롤링 배포 중 구버전 프레임(또는 #398 배포 전
  레거시)과의 호환성을 위해서다. CLAUDE.md의 "필드 추가만 허용, 제거·의미 변경 안 함" 원칙과 같은
  맥락.
- **refetch 대상은 `detailQuery`(`useGetDelivery`)**: `dev`의 현재 구조에서 상태·단계 표시가
  `useGetDeliveryTracking`이 아니라 `useGetDelivery` 하나로 통합돼 있어서, 이슈 본문의
  `trackingQuery.refetch()` 예시 대신 이 화면이 실제로 쓰는 쿼리를 refetch했다.

## 일부러 하지 않은 것

- **로컬 수동 확인(완료 조건)**: #398이 이 브랜치엔 커밋되지 않은 상태라 별도 브랜치로 분리했고,
  분리된 브랜치 단독으로는 실제 STATUS 프레임을 받아볼 백엔드가 없다 — 후속: #398 머지 후 재확인
  필요.
- **`useTrackingStream` 훅 자체의 EventSource 목킹 통합 테스트**: 기존 테스트 관례(순수 함수만
  테스트, 훅 렌더링 안 함)를 따라 `parseFrameType`을 export해 단위 테스트로만 검증했다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `shared/hooks/useTrackingStream.test.ts` | `parseFrameType`이 `status`/`location`/필드없음/JSON깨짐 4가지를 올바르게 판별 |

실행 결과:

```text
pnpm test → Test Files 20 passed (20), Tests 151 passed (151)
pnpm typecheck → 통과(출력 없음)
```

### 검증하지 못한 것

- 실제 백엔드(#398)가 STATUS 프레임을 발행하는 상황에서의 화면 수동 확인(브라우저 새로고침 없이
  단계 갱신). #398이 이 브랜치에 없어 재현 불가능했다.

## 새로 생긴 미결 사항

- #398 머지 후, `feature/399-status-sse-tracking-frontend`를 그 위로 리베이스/머지해 실제
  STATUS 프레임으로 로컬 수동 확인(완료 조건)을 마쳐야 한다.
