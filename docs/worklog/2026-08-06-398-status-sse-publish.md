# 배송 상태 전이 SSE 실시간 발행(백엔드) 작업 기록

- 이슈: [#398](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/398) (메인 [#397](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/397), 프론트 서브 [#399](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/399))
- 브랜치: `feature/398-status-sse-publish`
- 범위: backend
- 작성일: 2026-08-06

## 무엇을 만들었나

배송 상태 전이(배차 확정/이동 시작/픽업/배송 시작/완료)가 성공한 직후, 기존 위치 SSE와 같은
`tracking:order:{id}` Redis Pub/Sub 채널로 상태 변경 이벤트를 발행한다. `LocationPayload`/
`StatusChangedPayload`에 `type` 판별 필드(`"location"`/`"status"`)를 추가해 프론트가 같은 채널에서
두 프레임을 구분할 수 있게 했다. 새 엔드포인트는 없다 — 기존 API의 트랜잭션에 발행 한 줄씩만 얹었다.

### API

기존 엔드포인트 계약은 그대로다. 성공 시 부수 효과(SSE 발행)만 추가됐다.

| 메서드 | 경로 | 부수 효과 추가 |
|---|---|---|
| POST | `/api/rider/requests/{deliveryId}/accept` | 성공 시 `status=ASSIGNED` 이벤트 발행 |
| POST | `/api/rider/deliveries/{deliveryId}/transition` | 성공 시 해당 상태(`MOVING_TO_PICKUP`/`PICKED_UP`/`DELIVERING`) 이벤트 발행 |
| POST | `/api/rider/deliveries/{deliveryId}/complete` | 성공 시 `status=COMPLETED` 이벤트 발행 |

### 화면

해당 없음 — 프론트 소비는 #399.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 같은 `tracking:order:{id}` 채널 재사용 vs 새 채널

- **물었던 것**: Discussion #375에서 이미 사람(bigbell999)이 "sse 연결을 그대로 사용하는 것이 좋아
  보인다"고 결론 내림 — 이 이슈에서 다시 게이트를 열지 않았다.
- **선택지**:
  - (A) 같은 채널, `type` 필드로 구분 — 팬아웃·구독·레지스트리 재사용, CLAUDE.md의 "채널 접두어도
    배포 호환성 표면" 원칙 유지
  - (B) 새 채널(`tracking:order:{id}:status`) — 위치와 완전히 독립되지만 구독 로직을 두 배로
    늘려야 한다(패턴 구독 두 개, 또는 emitter당 두 채널 매핑)
- **고른 것**: (A)
- **근거**: Discussion #375 원문. "type 필드를 넣어서 'location', 'status' 로 구분하고 각 타입에
  따라 publish 메서드를 두 개로 구분"
- **영향**: 프론트(#399)는 하나의 `EventSource`에서 `onmessage` 페이로드의 `type`만 보고 분기하면
  된다 — 두 번째 연결이 필요 없다.

### 2. 프레임 판별자 값의 대소문자

- **물었던 것**: 게이트에서 묻지 않음 — 초안 설계(대화 중 plan) 때는 `"LOCATION"`/`"STATUS"`
  대문자로 썼었는데, 이슈를 실제로 작성하며 Discussion #375 원문을 다시 확인하니 소문자
  `'location'`/`'status'`였다.
- **고른 것**: 원문 그대로 소문자 `"location"`/`"status"`.
- **근거**: 이미 사람이 디스커션에서 구체적으로 정한 값이라 재해석하지 않고 그대로 따름.
- **영향**: #399 프론트 구현 시 `type === 'status'`(소문자)로 분기해야 한다 — 대문자로 구현하면
  아무 프레임도 안 걸린다.

### 3. CANCELED는 발행하지 않는다

- **물었던 것**: 게이트에서 묻지 않음 — CLAUDE.md 확정 정책("배차 이후 취소는 MVP 범위 제외",
  추적 스냅샷 API가 WAITING을 409로 막음)으로 이미 답이 있었다.
- **고른 것**: WAITING→CANCELED 전이는 발행 대상에서 제외.
- **근거**: 그 전이가 일어나는 시점(WAITING)엔 고객 추적 화면 자체가 열리지 않아(스냅샷 API가
  409) SSE 구독자가 존재할 수 없다 — 발행해도 아무도 못 받는다.
- **영향**: `DeliveryService.cancelDelivery()`/`DeliveryTimeoutService`는 이번 변경에서 건드리지
  않았다.

## 스스로 판단한 것

- **`LocationPayload`에 5번째 레코드 컴포넌트(`type`)를 추가하면서 기존 4-인자 생성자를 남겼다**:
  이 레코드는 SSE 팬아웃 JSON뿐 아니라 `RiderLocationRepository`의 쉼표 구분 Redis 저장 형식에도
  쓰이는데, 그 `encode`/`decode`는 4개 필드만 위치 인자로 읽는다(코드 안 ponytail 주석 참고). 필드를
  추가해도 그 저장 형식엔 영향이 없음을 먼저 코드로 확인한 뒤, 기존 생성 호출부(~10곳, 프로덕션 2곳 +
  테스트 다수)를 전혀 건드리지 않고 타입 필드를 더하는 쪽을 골랐다.
- **`TrackingPublisher.publish`를 사설 헬퍼로 리팩터**: `publish(LocationPayload)`와
  `publishStatus(...)`가 직렬화 + `convertAndSend` + 예외 스월로우 로직을 그대로 공유하므로, 그
  블록만 `private void publish(Long, Object)`로 뽑았다. 범위를 넘는 리팩터는 아니다(같은 파일, 같은
  메서드 두 개가 생기며 자연히 필요해진 중복 제거).
- **`RiderDeliveryService.transition()`의 공통 성공 경로에 발행 한 줄만 추가**: `START_MOVING_TO_PICKUP`/
  `PICK_UP`/`START_DELIVERING` 세 액션이 이미 같은 메서드의 switch 뒤 공통 경로(로그 남기는 지점)를
  지나므로, 액션별로 세 번 반복하지 않고 그 지점 한 곳에 붙였다.

## 일부러 하지 않은 것

- **`order_status_history` 테이블 채우기**: 여전히 아무 코드도 안 쓴다(엔터티만 있고 리포지토리
  없음). 이번 이슈와 무관 — 후속 미등록.
- **배송 완료 시 SSE emitter를 서버가 능동적으로 닫는 것**: 이 백엔드 이슈는 발행까지만 한다.
  프론트(#399)가 STATUS 프레임 수신 시 재조회 → `isTrackable` 전이 → 기존 `useTrackingStream`의
  `enabled=false` 정리 effect로 클라이언트 쪽에서 닫는 설계다. 서버측 강제 종료는 요청받지 않았고
  이 설계로 충분해 추가하지 않았다.
- **발행 실패 시 재시도·알림**: 기존 위치 발행과 동일한 best-effort 정책(예외 삼키고 로그만) 그대로
  따랐다. 상태 이벤트도 유실되면 고객은 REST 재조회(다음 화면 진입·수동 새로고침)로 결국 정본 상태를
  본다 — 별도 이슈로 올리지 않았다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `TrackingPublisherTest` | `publishStatus` 직렬화(`type`/`status`/`occurredAt` 필드), Redis 발행 실패 시 예외를 밖으로 내지 않음 |
| 단위 | `RiderDeliveryRequestServiceTest` | `acceptDeliveryRequest` 두 조건부 UPDATE 성공 시 `publishStatus(deliveryId, ASSIGNED, ...)` 호출 검증 |
| 통합 | `RiderDeliveryServiceIntegrationTest`, `RiderDeliveryRequestServiceIntegrationTest`(기존 스위트 재실행) | 실제 `TrackingPublisher` 빈(로컬 Redis)으로 전이·완료 흐름에 회귀가 없음 확인 |
| E2E | `TrackingFanoutMultiInstanceE2ETest`(신규 케이스 1개 추가: `deliversStatusChangeAcrossInstances`) | 2인스턴스 중 B에서 실제 HTTP로 전이(`START_MOVING_TO_PICKUP`) 발생 → A에 연결된 고객 SSE가 `"type":"status"`, `"status":"MOVING_TO_PICKUP"` 프레임을 수신 — 위치와 같은 Pub/Sub 팬아웃 경로를 탄다는 것의 유일한 자동 증거 |

실행 결과:

```text
./gradlew test (전체 스위트, 로컬 Docker MySQL 8.4 + Redis 7.4)
→ BUILD SUCCESSFUL in 2m 47s
→ 581 tests, 0 failures, 0 errors (test-results/test/*.xml 집계)
```

### 검증하지 못한 것

- 프론트가 이 프레임을 실제로 파싱해 재조회를 트리거하는지는 #399(프론트 서브 이슈) 범위 — 이
  이슈는 "발행되고, 같은 채널로 도달한다"까지만 검증한다.
- 여러 상태 전이가 짧은 간격으로 몰릴 때 팬아웃 디스패처 풀(크기 4)에서 프레임 순서가 뒤집히는
  경우는 검증하지 않았다 — LOCATION 프레임도 이미 같은 한계를 안고 있다(CLAUDE.md 기존 미결 항목).

## 새로 생긴 미결 사항

- 없음. 이번 변경은 기존 CLAUDE.md 미결 항목(팬아웃 디스패처 순서 미검증, SSE emitter 능동 종료
  없음 등)의 범위를 넓히지 않았다 — "SSE emitter 능동 종료 없음" 항목은 프론트(#399)가 재조회 기반
  설계로 사실상 우회하지만, 서버가 직접 닫는 것은 아니므로 CLAUDE.md 문구 자체는 그대로 둔다.
