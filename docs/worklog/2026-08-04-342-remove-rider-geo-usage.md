# riders:geo 라이더-측 사용처 제거 작업 기록

- 이슈: [#342](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/342)
- 브랜치: `feature/342-remove-rider-geo-usage`
- 범위: backend (refactor / 제거)
- 작성일: 2026-08-04

## 무엇을 만들었나

디스커션 [#338](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/338)에서
"배차 위치 검색은 라이더 위치 기준 주변 주문 검색(pull) 한 방향만 두고, 주변 라이더 검색(#101)은
구현하지 않는다"로 확정됐다. 그 귀결로 라이더를 Redis GEO(`riders:geo`)에 넣을 이유가 사라져,
**`riders:geo`에 라이더를 쓰거나 읽는 코드를 전부 제거**했다. `RiderGeoRepository` 클래스 자체는
건드리지 않고 호출자 0인 상태로 남겨, 이름 변경(#339)·주문 저장소 재사용(형제 이슈 ③)에서
재활용되도록 했다.

제거한 라이더-측 사용처:
- `RiderLocationService.syncRedisState` — AVAILABLE→`registerOrUpdate` / BUSY→`remove` 제거.
  위치 갱신을 **BUSY 전용**으로 좁혀(허용 상태 `{BUSY}`), AVAILABLE·UNAVAILABLE 은 409.
- `RiderOperatingStatusChangeService`(#54 운행 종료) — `riderGeoRepository.remove(riderId)` 제거.
- `RiderDeliveryRequestService`(#56 배차 수락) — 확정 직후 `riderGeoRepository.remove` 제거.
- `RiderDeliveryRequestService`(#55 콜 목록) — `findPosition(self)` 제거. 라이더 좌표를 알 수
  없으므로 콜 목록은 항상 위치 없음으로 degrade한다.

### API

계약 변경 없음. `GET /api/rider/requests`의 `radiusMeters`·`sort` 파라미터는 그대로 남기되,
라이더 좌표 소스가 없어 반경 필터가 실질적으로 적용되지 않는다(거리 null, DISTANCE 요청은
REQUESTED_AT 로 대체). `POST /api/rider/location`은 이제 BUSY 가 아니면 409("배송 수행 중(BUSY)이
아닙니다.").

### 화면

해당 없음(프론트 계약 변경 없음, Orval 재생성 없음).

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 콜 목록의 라이더 좌표 소스를 이 이슈에서 어떻게 처리할지

- **물었던 것**: `findPosition(self)`를 없애면 라이더 좌표 소스가 사라진다. 이 이슈에서 좌표를
  요청 파라미터로 받는 계약 변경까지 할지, 아니면 degrade만 하고 계약 변경은 미룰지.
- **선택지**:
  - (A) 지금은 degrade만 — API 계약·프론트·Orval 무변경. 좌표 소스는 콜 목록 개편(형제 이슈 ③)에서.
  - (B) 지금 lat/lng 요청 파라미터 추가 — API 계약 변경 + Orval 재생성 + 프론트 연동. blast radius 큼.
- **고른 것**: (A)
- **근거**: 사람 확인 — 디스커션 #338 방향대로 "라이더 좌표는 주문 생성/검색 시점에 요청으로
  전달"할 것이고, **그 계약 변경은 이번 이슈 범위가 아니다.** 이 이슈는 "라이더-측 GEO 호출자
  제거"만 한다(이슈 본문 「하지 않는 것」과 일치).
- **영향**: 콜 목록은 계약 변경 전까지 반경 필터·거리 표시가 비활성(항상 전체 반환, 거리 null).
  `toSummary`/`withinRadius`/`comparatorFor` 플럼빙은 남겨 뒀고(이슈 ③이 요청 좌표를 소스로
  되살릴 자리), 지금은 `Optional.empty()`로 진입한다.

### 2. GEO 제거 후 AVAILABLE 위치 갱신 요청을 어떻게 할지

- **물었던 것**: GEO 제거 후 AVAILABLE POST는 저장·발행할 게 없는 no-op이 된다. 허용 상태를
  no-op 200으로 둘지, 409로 거부할지.
- **선택지**:
  - (A) 허용 유지, no-op 200 — 안드로이드 AVAILABLE 전송이 계속 200. 하지만 아무 일도 안 하는 경로.
  - (B) AVAILABLE 거부(409) — "위치 전송은 BUSY 전용"이라는 #338 방향과 정렬. 클라이언트 계약 변경 수반.
- **고른 것**: (B) — 허용 상태를 `{BUSY}`로 좁힘.
- **근거**: 사람 확인 — 디스커션 #338이 "AVAILABLE 라이더 위치는 배경 스트리밍·저장을 하지 않는다.
  위치 POST는 BUSY 전용이 된다"로 확정했고, **안드로이드 클라이언트가 이미 AVAILABLE 전송을
  없앴다**(커밋 `6ef5f57`, #341 — 플러그인·서비스 양쪽에서 AVAILABLE 시작 요청 거부). 서버가
  no-op 200으로 남겨 두면 "언젠가 새는" 여지가 남으므로, 클라이언트와 같은 방향으로 서버도 거부한다.
- **영향**: `LOCATION_ALLOWED_STATUSES` 상수 제거하고 `!= BUSY → 409` 부정형 검사로 단순화.
  거부 메시지가 "운행 중이 아닙니다." → "배송 수행 중(BUSY)이 아닙니다."로 바뀐다(테스트·프론트에
  이 문자열을 파싱하는 곳은 없음, E2E 단언만 갱신).

## 스스로 판단한 것

- **`findPosition` 제거를 코드 삭제가 아니라 `Optional.empty()` 진입으로**: 콜 목록의 거리/반경/정렬
  플럼빙(`toSummary`·`withinRadius`·`comparatorFor`)을 통째로 걷어내지 않고, 좌표 소스만
  `Optional.empty()`로 바꿨다. 근거: 이슈 본문이 "라이더-측 호출자 제거만"으로 범위를 못박았고,
  형제 이슈 ③이 요청 좌표를 소스로 이 플럼빙을 되살릴 것이라 지금 걷어내면 곧 다시 만들어야 한다.
  기존 graceful-degrade 코드(#55에서 이미 테스트됨)를 그대로 재사용한다.
- **`RiderGeoRepository` 클래스·`RiderGeoRepositoryTest`는 그대로 유지**: 이슈가 명시적으로 범위 밖.
  호출자 0으로 남는다. 다른 파일의 javadoc 언급(`RedisMessageListenerConfig`,
  `RiderLocationRepository`, `TrackingChannel`)도 클래스가 존재하는 한 정확하므로 손대지 않았다.
- **`RiderLocationRepository`(BUSY 최신 위치, `rider:location:*`)는 유지**: `riders:geo`(GEO)와
  다른 저장소다. BUSY 최신 위치 저장·추적 발행 경로는 그대로 남겼다.
- **`syncRedisState` 헬퍼를 `saveLatestLocation`으로 축소**: GEO 쓰기가 빠지면서 "GEO + 최신 위치를
  한 번에 묶는다"는 존재 이유가 사라져, BUSY 최신 위치 저장만 남는 헬퍼로 이름·역할을 좁혔다.
  Redis 실패 삼킴(로깅) 동작은 유지.

## 일부러 하지 않은 것

- **콜 목록 좌표를 요청 파라미터로 받는 계약 변경**: 이유 — 사람 결정(위 선택 1), 이슈 범위 밖.
  후속: 형제 이슈 ③(주문 GEOSEARCH 개편)에서.
- **주문 픽업지 GEO 등록·GEOSEARCH**: 이유 — 이슈 ③ 범위. 후속: ③.
- **`RiderGeoRepository` 이름 변경**: 이유 — 이슈 #339 범위. 후속: #339.
- **`#52`(세션 만료 시 GEO 정리)**: 지울 `riders:geo`가 사라져 불필요해짐(디스커션 #338 「소멸되는 것」).

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderLocationServiceTest` | 위치 갱신 BUSY 전용(비-BUSY 409), BUSY 최신 위치 저장·발행, Redis/DB 실패 격리 |
| 단위 | `RiderDeliveryRequestServiceTest` | 콜 목록 항상 degrade(거리 null·반경 스킵·FARE 정렬), 수락에서 GEO 제거 |
| 단위 | `RiderOperatingStatusChangeServiceTest` | GO_OFFLINE 이 상태 전이만(GEO 정리 없음), 멱등·BUSY 거부 |
| 통합 | `RiderDeliveryRequestServiceIntegrationTest` | 좌표 없이 반경 필터 미적용·거리 null, 수락 동시성(ADR-006) 유지 |
| 통합 | `RiderOperatingStatusChangeServiceIntegrationTest` | 운행 종료 UNAVAILABLE 영속(GEO 검증 삭제) |
| E2E | `RiderLocationUpdateE2ETest` | AVAILABLE·UNAVAILABLE 409, BUSY 200 + `rider:location` TTL 저장 |
| E2E | `RiderOperatingStatusChangeE2ETest` | GO_ONLINE/GO_OFFLINE 200·영속, BUSY 409, 멱등 |

실행 결과:

```text
./gradlew test --tests '<위 7개 클래스>' + RiderGeoRepositoryTest → 전부 통과 (BUILD SUCCESSFUL)
./gradlew test (전체) → 517 tests, 6 failed
  - 실패 6건은 전부 CustomerDeliveryCreateE2ETest (고객/주문, #342 무관)
  - dev 베이스라인(git stash 후)에서도 동일하게 실패 → #342가 원인이 아님(선행 회귀)
```

### 검증하지 못한 것

- **전체 스위트 그린을 만들지 못했다.** 6건 실패는 `CustomerDeliveryCreateE2ETest`이고,
  원인은 `CustomerWebMvcConfig`의 `/api/customer/deliveries/*`(#46, 커밋 `49881db`) 등록이
  공개여야 할 형제 경로 `/quote`를 Spring `/*`(한 세그먼트) 매칭으로 삼켜 401을 만드는 **선행
  회귀**다. #342 범위 밖이라 고치지 않고 별도 태스크로 분리했다(CI가 `-x test`라 놓친 회귀).

## 새로 생긴 미결 사항

- **`RiderGeoRepository`가 호출자 0으로 남았다** — #339(이름 변경)·형제 이슈 ③(주문 GEO 재사용)에서
  다룬다. 그때까지 데드 코드처럼 보이지만 의도된 상태다.
- **콜 목록 반경 필터·거리 표시가 비활성** — 라이더 좌표 소스(요청 파라미터)가 붙는 이슈 ③ 전까지
  `GET /api/rider/requests`는 전체 반환·거리 null·DISTANCE→REQUESTED_AT 대체로 동작한다.
- **선행 회귀: `/quote`가 인증 인터셉터에 잡혀 401** — `CustomerWebMvcConfig`
  `/api/customer/deliveries/*`(#46)가 `/quote`를 삼킨다. `CustomerDeliveryCreateE2ETest` 6건 실패.
  #342 무관, 별도 이슈로 처리 필요.
