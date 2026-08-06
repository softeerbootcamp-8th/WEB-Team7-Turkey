# 콜 목록 위치 검색에 좌표 파라미터·인덱스 반영 작업 기록

- 이슈: [#367](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/367)
- 브랜치: `feature/367-rider-call-list-location-search`
- 범위: backend
- 작성일: 2026-08-05

## 무엇을 만들었나

`GET /api/rider/requests`(콜 목록 조회, #55)가 라이더의 실제 좌표를 입력받지 못하고, 위치 필터링을
전부 애플리케이션 레벨에서 처리하던 상태를 고쳤다. 라이더 좌표(`latitude`/`longitude`)를 요청
파라미터로 받고, V10부터 있었지만 실제로 선택된 적이 없던 위치 인덱스
(`idx_delivery_waiting_location`)를 bounding box 쿼리로 실제로 활용하게 했다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/rider/requests` | 콜 목록 조회 — `latitude`/`longitude` 파라미터 추가(선택) | 400 좌표 범위 밖(-90~90/-180~180) |

기존 엔드포인트의 파라미터 확장이라 새 경로는 없다.

### 화면

해당 없음. 프론트가 GPS 좌표를 실제로 실어 보내는 연동은 이 이슈 범위 밖이다.

### 스키마 변경

해당 없음. `idx_delivery_waiting_location` 인덱스는 V10에 이미 있었다 — 이번에 처음 그 인덱스를
타는 쿼리를 만들었을 뿐이다.

## 사람이 고른 선택

### 1. 좌표가 없으면 어떻게 처리할까

- **물었던 것**: 요청에 `latitude`/`longitude`가 없으면 (A) 400으로 거부할지, (B) 기존 #55
  정책대로 위치 없음으로 degrade(반경 필터 스킵, 전체 반환)할지.
- **선택지**:
  - (A) 필수로 받는다(400 거부) — 모든 호출이 실제 위치 기반으로 동작함을 보장하지만, 프론트가
    아직 좌표를 안 보내면 이 API를 아예 못 쓰게 된다(#55 원래 계약을 깨뜨림).
  - (B) 선택으로 받는다(없으면 전체 반환) — 하위 호환은 유지되지만, 좌표 없는 요청은 페이지네이션이
    없는 지금 WAITING 전체가 그대로 나갈 수 있다.
- **고른 것**: (B)
- **근거**: 페이지네이션은 #60(필터/정렬 확장)에서 다루기로 이미 범위를 나눴고, 그 사이(#367
  배포~#60 배포) 좌표 없는 요청이 전체를 반환하는 창은 감수하기로 함(사람 확인). 필수로 만들면
  #55의 기존 "위치 미확보를 에러로 취급하지 않는다"는 계약을 이번 이슈가 깨뜨리게 된다.
- **영향**: #60이 페이지네이션을 도입할 때 이 "좌표 없음 → 전체 반환" 경로도 자연히 상한이 걸리게
  된다. CLAUDE.md의 「확인이 필요한 항목」에 이 연결 관계를 남겨 뒀다.

### 2. bounding box 쿼리를 JPA 파생 메서드로 둘지, FORCE INDEX 네이티브 쿼리로 바꿀지 (PR #378 리뷰, 2026-08-06)

- **물었던 것**: bigbell999가 PR #378 리뷰에서 두 가지를 제안함 — ① 파생 메서드 이름
  (`findByStatusAndPickup_LatitudeBetweenAndPickup_LongitudeBetween`)이 길어 가독성이 떨어지니
  `@Query`로 바꿀 것, ② discussion #380 실측(합성 데이터, MySQL vs Redis GEO 성능 비교)에서
  WAITING이 테이블 전체의 100%에 가깝고 절대 건수가 약 2만 건을 넘으면 옵티마이저가
  `idx_delivery_waiting_location` 대신 `idx_delivery_status_requested`로 갈아타 응답 시간이
  77배까지 뛰는 현상(#380 7장)을 확인했으니, `FORCE INDEX`로 항상 위치 인덱스를 강제할 것.
- **선택지**:
  - (A) 그대로 둔다 — 코드 변경 없음. 다만 위 절벽 현상에 그대로 노출된다.
  - (B) `@Query`로 가독성만 개선한다 — 절벽 현상은 그대로 남는다.
  - (C) `@Query` + `FORCE INDEX`로 둘 다 반영한다.
- **고른 것**: (C)
- **근거**: #380 4-2장 실측에서, 인덱스를 강제해도 정상 범위(WAITING이 적을 때)에서 성능 손해가
  없었다(13.9ms 자연 선택 vs 14.6ms 강제, 사실상 동일). `delivery_order`는 COMPLETED·CANCELED가
  영구히 누적되는 테이블이라 WAITING이 100%에 가까워지는 절벽 조건 자체가 실제 운영에서 발생할
  가능성은 낮다고 판단했지만, 강제 지정에 비용이 안 든다는 게 실측으로 확인된 이상 예방적으로
  적용하지 않을 이유가 없다고 봄(사람 확인, emes-g).
- **영향**: `FORCE INDEX`는 MySQL 고유 문법이라 JPQL로 표현할 수 없다 — 파생 메서드를
  `nativeQuery = true`인 `@Query`로 바꿔야 했다. 메서드명도 `findWaitingOrdersWithinBoundingBox`로
  줄였다(어차피 이 메서드는 WAITING 전용이라, 같은 파일의 다른 네이티브 쿼리들처럼 `status` 값을
  파라미터로 안 받고 SQL에 `'WAITING'`으로 고정했다 — 관례 통일). 실제로 `FORCE INDEX`가 걸리는지는
  `EXPLAIN`으로 수동 확인했다(`possible_keys`가 `idx_delivery_waiting_location` 하나로 좁혀짐).

## 스스로 판단한 것

- **`RiderDeliveryRequestApi`를 새 컨트롤러/인터페이스 분리 스타일로 마이그레이션하지 않음**:
  `backend.md`는 "인터페이스에 매핑·바인딩이 있는 옛 파일을 손보게 되면 새 형태(인터페이스는
  문서 전용, 매핑·바인딩은 컨트롤러)로 옮기라"고 하지만, 이 인터페이스의 클래스 레벨
  `@RequestMapping`과 나머지 세 메서드(`getDeliveryRequest`/`acceptDeliveryRequest`/
  `skipDeliveryRequest`)는 이번 이슈 범위가 아니다. 그 세 메서드까지 건드리지 않고
  `getDeliveryRequests` 하나만 새 스타일로 옮기면 클래스 레벨 매핑이 인터페이스·컨트롤러 어느
  쪽에 있어야 하는지 애매해져 위험도가 더 커진다고 판단해, 기존(레거시) 패턴을 그대로 확장하는
  쪽을 택했다 — `latitude`/`longitude`도 기존 `radiusMeters`/`sort`와 같은 자리(인터페이스의
  `@RequestParam`)에 추가했다.
- **`@Validated`를 `RiderDeliveryRequestController`에 추가**: `latitude`/`longitude`의
  `@DecimalMin`/`@DecimalMax` 제약이 인터페이스 메서드 파라미터에 있는데, Bean Validation의
  메서드 검증은 인터페이스에 선언된 제약을 구현체가 상속받는 것으로 취급한다(JSR-380 스펙 —
  하위 타입이 제약을 완화할 수 없다는 규칙의 이면). `@Validated`는 그 AOP 인터셉터를 활성화하는
  스위치라 구현체(빈)에 달아야 한다. E2E 테스트(범위 밖 좌표 → 400)로 실제 동작을 확인했다.
- **bounding box 계산을 `DeliveryService`에 둠**: 이미 하버사인 거리 계산(`distance`)과
  `EARTH_RADIUS` 상수를 갖고 있어서, 반경↔도(degree) 환산도 같은 구면 기하 전제(지구 반지름
  기준)를 써야 두 계산이 서로 어긋나지 않는다. 이미 있는(그러나 `@Deprecated`된)
  `pureStraightDistance`처럼 위도별 고정 km 상수를 하드코딩하는 방식은 쓰지 않았다 — 그 방식이
  왜 deprecated됐는지(위도에 따라 부정확) 같은 이유로 새로 만들지 않을 이유였다.
- **`BoundingBox`를 `DeliveryService`에 중첩된 public record로 둠**: `RiderDeliveryRequestService`가
  다른 패키지(`rider.service`)에 있어 이 값을 그대로 받아 리포지토리에 넘겨야 한다. 새 DTO
  파일을 따로 만들 만큼 API 계약에 노출되는 값이 아니라(순수 내부 계산 결과), `DeliveryService`의
  `FareCalculation`(같은 클래스 안에 private record로 계산 결과를 담는 기존 패턴)과 같은 자리에
  두되 접근 제어자만 `public`으로 뒀다.
- **`getDeliveryRequests`에 `operationId` 추가**: 원래 이 메서드의 `@Operation`에는
  `operationId`가 없었다(사전 존재하던 gap, `backend.md`가 새 컨트롤러엔 필수라고 명시한 항목).
  이번에 이 메서드의 파라미터를 바꾸며 `@Operation`을 어차피 편집하는 김에 같이 채웠다 — 이
  이슈 범위를 벗어나는 별도 리팩터가 아니라, 손대는 지점에 이미 있던 결함을 같이 고친 것이라
  판단했다. 나머지 세 메서드는 손대지 않았다.
- **인덱스 관련 오해를 실측으로 정정**: 애초에 "콜 목록 조회가 인덱스를 전혀 못 탄다"고 판단하고
  작업을 시작했으나, `EXPLAIN`으로 실제로 확인해보니 `findByStatus(WAITING)`은 이미
  `idx_delivery_status_requested`(status, requested_at)를 타고 있었다(전수 스캔이 아니었다).
  로컬 DB에 실제 서비스와 비슷한 상태 분포(COMPLETED 19만·CANCELED 2만·WAITING 8천, WAITING이
  전체의 3.7%)로 합성 데이터를 넣어 재확인한 결과, 그 인덱스 사용 자체는 이미 효율적이었다
  (전수 스캔 대비 실측 약 14배 빠름, `EXPLAIN ANALYZE`: 11.5ms vs 218ms). 진짜 문제는 인덱스
  선택이 아니라 **"WAITING 전체"라는 조건 자체가 이 화면(반경 내 검색)에는 너무 넓어서, 그 결과를
  전부 애플리케이션으로 가져와 하나하나 거리 계산해야 했다**는 것이었다. bounding box 조건을
  추가하니 MySQL이 `idx_delivery_waiting_location`으로 자동 전환했고(`Using index condition`,
  Index Condition Pushdown 확인), 좁은 반경(3km) 기준 후보가 8000건→1건으로, 시간은 11.5ms→
  0.09ms로 줄었다. 이 실측 결과를 반영해 이슈 #367 본문의 원래 표현("인덱스 없이 스캔")도
  정정했다.

## 일부러 하지 않은 것

- **`radiusMeters` 상한 도입**: CLAUDE.md에 이미 미결로 남아 있던 항목이고, 이번 이슈의 계약
  ("좌표 파라미터화 + 인덱스 반영")에는 포함되지 않았다. 페이지네이션과 함께 다룰 사안이라
  #60으로 미뤘다.
- **페이지네이션**: #60(필터/정렬 확장)의 명시적 범위라 이 이슈에서 건드리지 않았다.
- **운임 범위·배송거리 범위 필터, 정렬 방향 파라미터화**: 전부 #60 범위. `RiderDeliveryRequestSummaryResponse`가
  이미 운임·배송거리 값을 담고 있어 #60에서 별도 계산 없이 필터만 추가하면 된다.
- **프론트 GPS 좌표 연동**: 이 백엔드 이슈는 API 계약만 바꾼다. 실제로 라이더 앱이 콜 목록을 열
  때 GPS를 읽어 이 파라미터에 실어 보내는 작업은 별도 프론트 이슈가 필요하다(미등록).
- **Redis GEO(#362) 대안과의 비교 실측**: bigbell999가 별도로 진행 중인 성능 비교 이슈이고, 이
  이슈는 그 결론과 무관하게(팀 합의로 bounding box 채택 확정) 독립적으로 진행했다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `DeliveryServiceTest.BoundingBoxTest` | 좌표 없으면 예외, 위도/경도 경계까지의 실거리가 반경(m)에 근접, 사각형 모서리가 반경보다 멀다 |
| 단위 | `RiderDeliveryRequestServiceTest.WithRiderPositionTest` | 좌표 있으면 bounding box 쿼리 사용(전수 스캔 미호출), 사각형 후보 중 반경 밖 제외, 좌표 하나만 있으면 degrade |
| 단위 | `RiderDeliveryRequestServiceTest.DegradedWithoutRiderPositionTest` | 좌표 없을 때 기존 degrade 동작(전체 반환, 거리 null, 정렬 대체) 회귀 확인 |
| 통합 | `RiderDeliveryRequestServiceIntegrationTest` | 실제 MySQL에서 반경 내/외 주문 필터링, bounding box 모서리(사각형 안·원 밖) 제외 |
| E2E | `RiderDeliveryRequestE2ETest` | 좌표 있으면 반경 내만 반환(200), 좌표 범위 밖이면 400 |

실행 결과:

```text
./gradlew test → BUILD SUCCESSFUL, 562 tests completed, 0 failed
```

### 검증하지 못한 것

- 실제 프로덕션 규모(다도시 동시 운영 등)에서의 부하 테스트 — 로컬 합성 데이터(21.8만 건)로
  방향성은 확인했으나, #362·#259 같은 정식 부하 테스트 이슈의 몫이다.
- 프론트 연동(GPS 좌표 실제 전송) — 이 이슈 범위 밖.

## 새로 생긴 미결 사항

- 좌표 없는 요청이 페이지네이션 없이 WAITING 전체를 반환하는 경로 — #60에서 페이지네이션 도입
  시 자연히 해소되는지 확인 필요(CLAUDE.md에도 추가함).
- 프론트가 이 API에 실제 좌표를 실어 보내는 연동 이슈 미등록.
