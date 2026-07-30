# [RIDE-QUICK-001] 퀵 요청 목록 보기 작업 기록

- 이슈: [#55](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/55)
- 브랜치: `feature/55-rider-quick-request-list`
- 범위: backend
- 작성일: 2026-07-30

## 무엇을 만들었나

AVAILABLE 라이더가 배차 대기(WAITING) 배송요청 목록을 조회하는 API를 구현했다. `RiderDeliveryRequestApi`
계약(#56/#57과 함께 이미 커밋돼 있던 인터페이스)의 `getDeliveryRequests` 하나만 실제로 구현하고, 같은
인터페이스의 나머지 세 메서드(상세·수락·넘기기)는 각자 이슈 범위라 스텁으로 남겼다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/rider/requests?radiusMeters=3000&sort=DISTANCE` | AVAILABLE 라이더에게 WAITING 요청 목록 반환 | 401 미인증, 403 라이더 상태가 AVAILABLE 아님, 400 반경/정렬 값 오류 |

### 화면

해당 없음 — 이슈에 화면 요구사항이 없어 백엔드 API만 구현했다(`rider/requests` 화면 연동은 별도 프론트 이슈).

### 스키마 변경

해당 없음 — 기존 `delivery_order`, `order_fare_snapshot` 테이블을 그대로 조회한다. 신규 Flyway 마이그레이션 없음.

## 사람이 고른 선택

### 1. 페이지네이션 유무

- **물었던 것**: 이슈 본문 입력값에 "페이지 정보"가 있지만, 이미 커밋된 `RiderDeliveryRequestApi.getDeliveryRequests`
  계약에는 `radiusMeters`, `sort`만 있고 page/size가 없음. 어느 쪽을 따를지.
- **선택지**:
  - (A) 기존 계약 유지(페이지네이션 없음) — 반경으로 건수가 자연히 제한됨, 계약 변경 불필요 / 건수가 많아지면 응답이 커질 수 있음
  - (B) 계약 수정해 page/size 추가 — 이슈 본문과 일치 / 이미 커밋된 계약을 되돌려야 하고 리뷰 범위가 커짐
- **고른 것**: (A)
- **근거**: MVP 스케일(수십~수백 건)에서는 단순함이 우선이고, 이미 머지된 계약을 건드리는 비용이 더 크다고 판단.
- **영향**: WAITING 주문이 크게 늘어나면 `getDeliveryRequests`가 매번 전체를 스캔·반환한다. 아래 「새로 생긴 미결
  사항」에 등록.

### 2. 라이더 위치 없음 처리

- **물었던 것**: 이슈 본문 예외처리는 "위치정보 없음/유효하지 않음 시 요청 거부"인데, 이미 커밋된 계약 주석은
  "위치가 없으면 거리 필드는 null"(정상 200)이라고 명시. 어느 쪽으로 구현할지.
- **선택지**:
  - (A) 계약 주석대로 null로 graceful degrade — RIDE-LOC-001(위치 쓰기, 진행 중)이 아직 안 끝나 위치 없는
    라이더가 실제로 많을 수 있어 실용적 / 이슈 문구와는 다름
  - (B) 이슈 본문대로 400 거부 — 이슈와 일치 / RIDE-LOC-001 완료 전까지 대부분의 라이더가 목록 자체를 못 봄
- **고른 것**: (A)
- **근거**: 위치 쓰기 경로가 별도 이슈로 아직 진행 중인 상황에서 (B)를 택하면 이 기능이 사실상 항상 실패한다.
- **영향**: `sort=DISTANCE`를 요청해도 위치가 없으면 `REQUESTED_AT`로 자동 대체된다(아래 스스로 판단한 것 참고).

## 스스로 판단한 것

- **AVAILABLE 아님 → 403(FORBIDDEN)**: 이슈·기존 코드에 선례가 없어 직접 결정. 인증은 됐지만 현재 상태가 행위를
  막는 상황이라 401/409보다 403이 의미상 맞다고 판단.
- **정렬 기본 방향**: FARE는 정산액 내림차순(높은 보수 먼저), REQUESTED_AT은 오름차순(오래 기다린 요청 먼저).
  이슈에 방향 명시가 없어 라이더 관점에서 자연스러운 쪽으로 정함.
- **expectedSettlementAmount = OrderFareSnapshot(ESTIMATE).totalFare 그대로**: `FareBreakdownResponse` 주석이
  "수수료 정책이 생기면 갈릴 수 있다"고 명시하듯 현재 수수료 정책이 없어 총 운임과 동일하게 뒀다. 수수료 정책이
  생기면 이 지점에서 차감 로직을 추가해야 한다.
- **`RiderDeliveryRequestApi.getDeliveryRequests`에 `AuthenticatedRider` 파라미터 추가**: 커밋돼 있던 계약
  4개 메서드 전부에 라이더 식별 파라미터가 빠져 있었다(순수 누락으로 보임). 기존 관례(`RiderPaymentController`의
  `@RequestAttribute(RiderSessionInterceptor.CURRENT_RIDER_ATTRIBUTE) AuthenticatedRider`)를 그대로 적용해
  이번에 쓰는 메서드에만 추가했다. 나머지 3개는 이슈 범위 밖이라 손대지 않음(아래 미결 사항 참고).
- **`RiderWebMvcConfig`에 `/api/rider/requests`, `/api/rider/requests/**` 등록 추가**: 등록이 안 돼 있어
  인증 없이 열릴 뻔했다(CLAUDE.md가 경고하는 바로 그 실수). 4개 메서드 전체 경로를 미리 등록해, #56/#57
  구현 시 이 등록을 또 빠뜨릴 여지를 없앴다.
- **Redis GEO 읽기를 `location/repository/RiderGeoRepository`에 배치**: `riders:geo`는 ERD에 이미 정의된
  공용 키라 `rider` 패키지가 아니라 `location` 패키지(위치 도메인)에 뒀다. RIDE-LOC-001(위치 쓰기, 진행 중)과
  같은 키를 읽기만 하며, 쓰기 경로와 충돌하지 않는다.
- **`DeliveryOrderRepository`/`OrderFareSnapshotRepository` 신규 생성**: 이전에 없었다(`order/repository`에는
  `FarePolicyRepository`만 있었음). `findByStatus`, `findByOrder_IdInAndFareType` 두 개만 우선 추가했다.

## 일부러 하지 않은 것

- **`getDeliveryRequest`(#57 상세)·`acceptDeliveryRequest`(#56 수락)·`skipDeliveryRequest`**: 컨트롤러에
  스텁(`return null`)으로만 남겼다 — 각자 이슈 범위. 후속: #56, #57.
- **주문 생성 플로우 연동**: `REQ-ORD-002`(배송 주문 생성, 담당 `githings`, 보드 상태 Todo)가 아직 구현되지
  않아, 실제 고객 생성 플로우로 WAITING 주문을 만들 수 없다. 테스트는 도메인 팩토리(`DeliveryOrder.request`,
  `OrderFareSnapshot.create`)로 직접 fixture를 만들어 검증했다. 후속: #55 자체는 아니고 REQ-ORD-002 완료를
  기다리는 통합 이슈.
- **라이더 위치 쓰기(Redis GEOADD) 연동**: `RIDE-LOC-001`(담당 `bigbell999`, In progress)이 아직 진행 중이라
  이 이슈 범위 밖으로 두고, 통합 테스트에서는 `riders:geo`에 직접 GEOADD해 "쓰기는 이미 되어 있다"는 상태를
  재현했다. 후속: RIDE-LOC-001 완료 후 실제 위치 갱신 경로와 함께 다시 확인 필요.
- **`radiusMeters` 상한 검증**: 0 이하만 막고 상한은 두지 않았다. 이슈에 상한 언급이 없고 악용 시나리오도
  현재 스코프에서 크지 않다고 판단. 후속: 미등록(필요해지면 새 이슈로).

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderDeliveryRequestServiceTest` | AVAILABLE 아님/반경 오류/정렬 오류 거부, 빈 목록, 운임 스냅샷 누락 시 정합성 오류, 반경 필터+거리 계산, FARE 정렬, 위치 없을 때 graceful degrade와 DISTANCE→REQUESTED_AT 대체 |
| 통합 | `RiderDeliveryRequestServiceIntegrationTest` | WAITING만 반환·ASSIGNED 제외(실제 MySQL), 실제 Redis GEO 반경 필터·거리 계산, 위치 없을 때 정상 200, 운임 스냅샷 조인 값 일치 |
| E2E | `RiderDeliveryRequestE2ETest` | AVAILABLE 라이더 200+목록, 세션 없음 401, AVAILABLE 아님 403 |

실행 결과:

```text
./gradlew test → BUILD SUCCESSFUL, 217 tests, 0 failures, 0 errors
  (이 이슈에서 추가한 16개: 단위 9 + 통합 4 + E2E 3 포함, 기존 201개 회귀 없음)
pnpm typecheck → 실행하지 않음(백엔드 전용 이슈, 프론트 파일 변경 없음)
```

### 검증하지 못한 것

- 브라우저 E2E는 하지 않았다 — 이슈에 화면이 없어 대상이 아니다.
- 이 API는 읽기 전용이라 동시성(경쟁) 테스트를 두지 않았다 — 상태를 바꾸지 않으므로 해당 없음.
- 이 세션 환경에 Docker가 없어 처음엔 통합·E2E를 실행하지 못했으나, 사용자 요청으로 colima를 설치해 로컬
  Docker MySQL 8.4 + Redis 컨테이너를 띄운 뒤 실제로 실행해 검증했다(위 결과 참고).

## 새로 생긴 미결 사항

- `RiderDeliveryRequestApi`의 `getDeliveryRequest`/`acceptDeliveryRequest`/`skipDeliveryRequest` 세
  메서드에도 라이더 식별 파라미터(`AuthenticatedRider`)가 빠져 있다 — #56/#57 구현 시 이번과 같은 방식으로
  추가해야 한다.
- `radiusMeters` 상한이 없다 — 필요 이상으로 큰 값을 보내면 전체 WAITING 주문을 스캔한다. 악용/성능 영향이
  실제로 드러나면 재검토.
- 페이지네이션 없이 전체 반환하기로 했다 — WAITING 주문이 크게 늘어나는 시점(예: 여러 도시 동시 운영)에
  성능이 문제되면 계약을 다시 열어 page/size를 추가해야 한다.
