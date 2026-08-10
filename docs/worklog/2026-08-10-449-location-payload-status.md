# 위치 데이터에 배송 상태 포함 작업 기록

- 이슈: [#449](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/449)
- 브랜치: `feature/449-location-payload-status`
- 범위: fullstack (백엔드 + `shared/hooks/useTrackingStream.ts`)
- 작성일: 2026-08-10

## 무엇을 만들었나

주기적으로 흐르는 라이더 위치 프레임에 현재 배송 상태를 실었다. 상태 전이 이벤트는 전이 시점에
한 번만 발행되고 Redis Pub/Sub은 저장하지 않으므로, 그 프레임이 유실되면 고객 화면이 실제 상태보다
한 단계 뒤처진 채 남는다. 이제 **다음 위치 프레임(BUSY 5초 주기)이 그 유실을 덮는다.**

적용 대상은 전이 이후에도 위치가 계속 흐르는 구간(`ASSIGNED`~`DELIVERING`)이다.
`WAITING→CANCELED`(#444)와 `DELIVERING→COMPLETED`(#450)는 그 순간 위치가 끊기므로 각각
다른 수단을 쓴다.

### API

새 엔드포인트 없음. 기존 SSE 팬아웃 페이로드에 필드 하나가 추가됐다(허용된 확장 — 팬아웃 계약은
필드 추가만 허용하고 제거·의미 변경은 금지).

### 화면

`tracking.tsx`는 **한 줄도 바꾸지 않았다.** 이미 `statusChangedAt` 신호를 받아 상세를 재조회하는
배선이 있어서, 훅 내부에서 그 신호가 더 많은 상황에 울리게 하는 것으로 끝났다.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

이슈 본문에 계약이 이미 확정돼 있어(설계 논의를 이슈 작성 전에 마쳤다) 게이트에서 물을 것이
없었다. 그 논의의 결론은 ADR 초안에 정리돼 있다.

핵심만 옮기면:

- **위치 프레임에 편승하는 이유**: 정합성을 위해 새 트래픽(폴링·heartbeat)을 만들지 않고,
  이미 다른 목적으로 흐르는 트래픽을 쓴다. 검토했던 대안 중
  ACK 프로토콜은 Redis Pub/Sub에 재전달 기능이 없어 큐가 공짜로 주는 것을 직접 구현해야 했고,
  Kafka는 보장 범위가 서버 인스턴스까지라 지배적 유실 지점(서버→브라우저)을 못 덮었으며,
  emitter TTL 단축은 자원 관리용 값을 정합성 노브로 전용하는 오용인 데다 재연결 공백으로
  위치 프레임이 약 9% 유실돼 주 기능을 훼손했다.

## 스스로 판단한 것

- **`TrackableDelivery`(기존 record)를 재사용하지 않고 `InProgressDelivery` 인터페이스를 새로 만들었다**
  — 이 조회는 생성 컬럼(`active_rider_id`)을 쓰느라 **네이티브 쿼리**이고, 네이티브는 JPQL 생성자
  표현식(`select new ...`)을 쓸 수 없다. 스프링 데이터의 인터페이스 투영은 네이티브에서도 동작한다.
  `TrackableDelivery`는 JPQL 전용이고 이 경로에 불필요한 `riderId`까지 들고 있다.

- **상태를 `String`으로 받아 서비스에서 변환한다** — 네이티브 쿼리의 VARCHAR를 enum으로 자동
  변환해 주는지는 스프링 데이터 버전·설정에 달렸다. 변환 지점을 호출자에 두어 명시적으로 만들고,
  그 동치(`getStatus()` == `OrderStatus.name()`)를 통합 테스트가 실제 DB로 고정한다.

- **`LocationPayload.status`를 nullable로 두고 `withStatus()` 사본 메서드를 추가했다** — 이 레코드는
  SSE 팬아웃과 Redis 최신 위치 저장 두 경로에서 쓰인다. 저장 쪽(`RiderLocationRepository.encode`,
  쉼표 구분 형식)에 상태가 섞이면 **Redis 값 형식이 바뀌어 배포 호환성 표면이 하나 더 는다.**
  그래서 저장에는 원본을, 팬아웃에는 사본을 넘긴다. 단위 테스트가 이 분리를 고정한다
  (`doesNotPersistStatusToLatestLocation`).

- **프론트에서 첫 프레임은 재조회를 트리거하지 않는다** — 화면은 이미 REST로 최신 상태를 읽고
  진입하므로, 첫 위치 프레임에서 신호를 울리면 진입 직후 불필요한 재조회가 한 번 더 돈다.
  `lastStatusRef === null`을 첫 프레임 판별에 쓴다.

- **`lastStatusRef`를 state가 아니라 ref로 뒀다** — 이 값이 바뀐다고 연결을 다시 맺을 이유가 없다.
  state로 두고 `useEffect` 의존성에 넣으면 상태가 바뀔 때마다 SSE가 재연결된다.

## 일부러 하지 않은 것

- **`WAITING→CANCELED`, `DELIVERING→COMPLETED`** — 그 전이가 일어나는 순간이 곧 위치가 끊기는
  순간이라 이 방식으로는 원리적으로 복구할 수 없다. 각각 #444, #450이 담당한다.
- **`onopen` 재조회** — 재연결 시 재동기화는 일반 규칙으로는 타당하지만, 재연결의 지배적 원인이
  emitter TTL이라 사실상 "TTL 주기 폴링"으로 동작한다. 이 이슈가 중간 전이를 이미 5초 안에
  덮으므로 실익이 겹쳐 넣지 않았다.
- **`refetchOnWindowFocus`** — `tracking.tsx` 소관이라 #444 범위다.
- **`/tracking` 스냅샷 API 정리** — 프론트에 호출자가 없는 상태지만 이 이슈와 무관하다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderLocationServiceTest` | 발행 프레임에 현재 상태가 실림 / **Redis 저장에는 상태가 안 들어감**(경로 분리 고정) |
| 통합 | `DeliveryOrderActiveRiderIntegrationTest` | 모든 상태를 실제 DB로 돌며 `getStatus()`가 `OrderStatus.name()`과 정확히 일치 — 어긋나면 호출부의 `valueOf`가 런타임에 터진다 |
| 프론트 | `useTrackingStream.test.ts` | `parseFrameStatus` — 정상 / status 없음(구버전) / null(Redis 복원값) / 문자열 아님 / JSON 아님 |

실행 결과:

```text
cd backend && ./gradlew test
→ BUILD SUCCESSFUL in 2m 47s, 639 tests, 0 failures, 0 errors

cd frontend && pnpm typecheck   → 통과
cd frontend && pnpm test        → 23 files, 175 tests 통과
```

### 검증하지 못한 것

- **인터페이스 투영이 운영 환경에서도 컬럼 별칭을 제대로 매핑하는지**는 로컬 MySQL 8.4 통합
  테스트로만 확인했다. 별칭(`AS orderId`)에 의존하므로 DB 벤더가 바뀌면 재확인이 필요하다.
- `status`가 실제로 유실된 전이를 복구하는 **엔드투엔드 시나리오**(전이 프레임을 인위적으로
  유실시키고 다음 위치 프레임으로 복구되는지)는 테스트하지 않았다. 유실을 인위적으로 만들려면
  팬아웃 경로에 실패 주입이 필요한데, 그 장치를 이 이슈에서 만들지 않았다.

## 새로 생긴 미결 사항

- **위치 갱신 조회가 index-only에서 행 조회 추가로 바뀌었다.** `order_id`는 보조 인덱스
  `uk_delivery_active_rider`에 PK로 포함돼 인덱스만으로 끝났지만, `status`는 인덱스에 없어
  클러스터드 인덱스 조회가 한 번 더 붙는다. 단일 행 PK 조회라 절대 비용은 작지만
  **BUSY 5초 주기 × 동시 배송 수**로 호출되므로 부하 테스트 확인 항목이다.
