# WAITING 구간 SSE 연결 허용 작업 기록

- 이슈: [#401](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/401) (메인 [#397](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/397))
- 브랜치: `feature/401-waiting-sse-connection` (스택: `dev` ← `feature/399-status-sse-tracking-frontend` ← 이 브랜치)
- 범위: fullstack
- 작성일: 2026-08-06

## 무엇을 만들었나

고객이 배송요청을 생성해 WAITING 상태로 추적 화면에 진입하는 시점부터 SSE 연결을 허용한다. 전에는
배차(ASSIGNED) 이후에만 연결이 열려서, WAITING→ASSIGNED 전이 자체를 실시간으로 알릴 방법이
구조적으로 없었다(그 전이를 알리려면 연결이 이미 열려 있어야 하는데, 연결을 열려면 그 전이가 먼저
일어나야 했다 — 순서가 거꾸로였다). #398이 만든 상태 전이 SSE 발행이 이번 변경으로 처음부터
의미를 갖게 된다.

### API

| 메서드 | 경로 | 변경 |
|---|---|---|
| GET | `/api/customer/deliveries/{id}/tracking/stream` | WAITING 이 이제 200(기존 409) |
| GET | `/api/customer/deliveries/{id}/tracking` | WAITING 이 이제 200, `riderName`/`riderPhoneNumber` null |
| GET | `/api/customer/deliveries/{id}/location` | WAITING 이 이제 200, `location` null(라이더 없음) |

세 엔드포인트 모두 `DeliveryTrackingAccessService.authorizeTracking()`을 공유해서 판정이 자동으로 같이 바뀐다.

### 화면

`routes/customer/_authed/deliveries/$deliveryId/tracking.tsx` — SSE 구독 조건(`useTrackingStream`의
`enabled`)을 지도 렌더링 조건(`isTrackableDeliveryStatus`, ASSIGNED~DELIVERING)에서 분리해
`isActiveDeliveryStatus`(WAITING 포함, COMPLETED/CANCELED 제외)로 연결했다. 지도·마커는 여전히
`isTrackable` 기준(라이더 위치가 있을 때만 의미 있음), SSE 연결 자체와 연결 상태 표시줄은
`isSubscribable` 기준으로 나뉜다.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. `isTrackable()`을 고치지 않고 `isTerminal()`을 새로 추가

- **물었던 것**: 게이트를 열 때 기존 `isTrackable()`의 정의를 넓힐지, 별도 판정을 추가할지.
- **선택지**:
  - (A) `isTrackable()`에 WAITING 추가 — 코드 한 곳만 고치면 되지만, 이 메서드는 V10 마이그레이션의
    `active_rider_id` 생성 컬럼 CASE 식과 1:1로 묶인 계약이라(Javadoc·`DeliveryOrderActiveRiderIntegrationTest`),
    건드리면 "구독 가능"과 "위치 발행 대상"이라는 서로 다른 두 개념이 섞인다.
  - (B) `isTerminal()`(COMPLETED/CANCELED만 true) 신설, 게이트는 `!isTerminal()`로 변경 —
    `isTrackable()`은 원래 의미(위치 발행 대상) 그대로 남는다.
- **고른 것**: (B)
- **근거**: 실제로 뜯어보니 이 두 개념이 이미 갈라져 있었다(WAITING은 위치는 없지만 상태는
  알고 싶은 상태) — 이번에 게이트만 바꾸고 위치 발행 판정은 그대로 둬야 향후 위치 관련 코드를
  건드릴 때 이 변경을 신경 쓰지 않아도 된다.
- **영향**: `DeliveryTrackingAccessService`, `DeliveryTrackingQueryService`, 프론트
  `tracking.tsx`만 변경했고 위치 발행 경로(`RiderLocationService`, `TrackingPublisher`)는
  전혀 건드리지 않았다.

### 2. 프론트 SSE 게이트 값으로 새 함수를 만들지 않고 기존 `isActiveDeliveryStatus` 재사용

- **물었던 것**: 없음 — 코드를 보니 정확히 필요한 의미(터미널 아님, WAITING 포함)의 함수가
  `shared/delivery/status.ts`에 이미 있었고(다른 화면의 이용기록 목록 분류용), 테스트까지
  갖춰져 있었다.
- **고른 것**: `isActiveDeliveryStatus`를 그대로 가져다 씀. 새 함수를 만들지 않았다.
- **근거**: 정확히 같은 boolean 의미("이 배송이 아직 안 끝났나")라 재구현하면 중복이다.

## 스스로 판단한 것

- **`CustomerLocationQueryService.getLocation()`에 `riderId == null` 가드 추가**: WAITING이
  게이트를 통과하게 되면서 `TrackableDelivery.riderId()`가 null일 수 있는 경로가 처음 생겼다.
  기존 `findLocationOrFail(riderId)`은 null을 고려하지 않고 바로 Redis 키를 만들었을 것이다 —
  Redis를 부르지 않고 바로 `location=null`로 답하도록 앞단에서 분기했다.
- **`DeliveryTrackingQueryService.getTracking()`의 `order.getAssignedRider().getMember()`를
  null-safe로 수정**: 이 줄은 "추적 가능 상태에서는 DDL이 라이더 존재를 강제한다"는 주석 아래
  라이더가 항상 있다고 가정하고 있었다 — 그 가정은 게이트가 `isTrackable()`(ASSIGNED~DELIVERING)만
  통과시킬 때는 참이었지만, WAITING이 통과하게 되면서 깨졌다. 실제로 통합 테스트 스위트를
  전체 실행해서(`DeliveryTrackingQueryServiceIntegrationTest$Rejected.rejectsNotTrackableStatuses`
  단계에서는 안 잡히고, 그 테스트 자체를 WAITING 제외로 고친 뒤 새로 추가한 성공 케이스에서) 이
  NPE 위험을 미리 잡았다 — 실제 배포 전에 코드로 확인한 것이지 추측이 아니다.
- **`DeliveryTrackingAccessServiceIntegrationTest`의 기존 `rejectsWaitingDeliveryAsConflict`를
  삭제하지 않고 `Allowed` 쪽으로 옮겨 `allowsWaitingDeliveryWithoutRider`로 다시 씀**: 이 테스트가
  원래 지키던 불변식(FK 컬럼만 읽는 조회라 WAITING 행이 사라지지 않는다)은 여전히 유효한
  회귀 방어라, 기대값만 409→200/riderId=null로 바꿔서 그 불변식 자체는 계속 잠근다.

## 일부러 하지 않은 것

- **CANCELED 발행 여부 재검토** — 메인 이슈(#397) 체크리스트에 남겨 둔 미결이다. WAITING에서도
  연결이 열리게 되므로 이론적으로는 WAITING→CANCELED(고객 취소) 시점에도 이미 열린 연결이
  있을 수 있어 발행이 의미를 가질 수 있지만, 이번 이슈 범위(#401)는 "연결을 여는 것"까지만이라
  발행 대상 확대는 별도 판단으로 남긴다.
- **heartbeat 도입** — 지난 대화에서 이미 짚었듯, WAITING 동안 연결이 훨씬 오래(배차 대기
  시간만큼) 침묵할 수 있어 heartbeat 부재의 영향이 커진다. 그래도 이번 이슈에서 새로 만들지
  않았다 — 기존에도 있던 미결 항목이고, 범위를 넘는 별도 작업이다. `CLAUDE.md` 「확인이 필요한
  항목」에 이미 있는 항목이라 중복 추가하지 않았다.
- **emitter TTL(5분) 연장 검토** — WAITING이 5분보다 오래갈 수 있어 emitter가 먼저 타임아웃될
  수 있다. 브라우저 `EventSource`가 자동 재연결하므로 기능적으로는 복구되지만(재연결 시
  `authorizeTracking`이 다시 통과), TTL 값 자체를 조정할지는 이번 범위 밖으로 남긴다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 통합 | `DeliveryTrackingAccessServiceIntegrationTest` | WAITING이 이제 허용되고(`allowsWaitingDeliveryWithoutRider`) `riderId`는 null, COMPLETED/CANCELED는 여전히 409 |
| 통합 | `DeliveryTrackingQueryServiceIntegrationTest` | WAITING 스냅샷이 라이더 null로 정상 조회됨(`returnsSnapshotForWaitingWithoutRider`), 완료·취소만 409로 축소(`rejectsTerminalStatuses`) |
| E2E | `CustomerTrackingStreamE2ETest` | WAITING 구독이 200 + 레지스트리 등록(`subscribingToWaitingDeliveryRegistersConnection`) |
| E2E | `CustomerLocationPollingE2ETest` | WAITING 폴링이 200 + `location:null`(`returnsNullLocationForWaitingDelivery`) |
| E2E | `CustomerDeliveryTrackingE2ETest` | WAITING 스냅샷이 200 + 라이더 필드 null, steps 1개(`returnsSnapshotWithoutRiderWhenWaiting`) |
| 프론트 | 별도 신규 없음 | `isActiveDeliveryStatus`는 기존 `status.test.ts`가 이미 검증. `tracking.tsx`는 이 저장소 관례상 라우트 컴포넌트 단위 테스트를 두지 않는다(백엔드 E2E가 실제 계약을 검증) |

실행 결과:

```text
cd backend && ./gradlew test → BUILD SUCCESSFUL, 580 tests, 0 failures, 0 errors
cd frontend && pnpm typecheck → 통과
cd frontend && pnpm test → 20 files, 151 tests 통과
```

### 검증하지 못한 것

- 실제 배차 대기 시간(수 분)만큼 연결이 침묵한 뒤 CloudFront 등 프록시 유휴 타임아웃에 끊기는
  시나리오는 로컬 테스트로 재현하지 못했다 — 배포 인프라가 있어야 확인 가능하다.

## 새로 생긴 미결 사항

- WAITING 구간 연결이 이전보다 훨씬 오래 침묵할 수 있어, 기존 「heartbeat가 없다」·「SSE emitter
  타임아웃 5분이 실제 정책값인지 미결」 항목의 실제 영향이 커졌다. 새 항목을 추가하기보다
  `CLAUDE.md`의 기존 두 항목에 이 사실을 반영해 둔다(별도 커밋으로 `CLAUDE.md` 갱신).
