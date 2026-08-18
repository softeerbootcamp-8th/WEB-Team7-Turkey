# 라이더 콜 목록 조회 엔터티→프로젝션 전환 작업 기록

- 이슈: [#559](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/559)
- 브랜치: `feature/559-rider-requests-projection`
- 범위: backend
- 작성일: 2026-08-18

## 무엇을 만들었나

`GET /api/rider/requests`(콜 목록 조회)의 리포지토리 조회 결과를 `DeliveryOrder` 엔터티 로딩에서
응답에 실제로 쓰는 8개 필드만 담는 JPA 프로젝션(`WaitingDeliverySummary`)으로 바꿨다. Discussion
#549의 JFR 프로파일링 결과(콜 목록 경로 CPU 샘플의 86.2%가 Hibernate/ORM, `DeliveryOrder`
29개 컬럼 중 실제로 쓰는 건 8개)를 그대로 이슈로 옮겨 구현했다. API 응답 스펙(`RiderDeliveryRequestSummaryResponse`)은
전혀 바뀌지 않았다 — 내부 조회 방식만 바뀐다.

### API

응답 스펙 변경 없음(해당 없음). 기존 `GET /api/rider/requests`가 내부적으로 프로젝션을 쓰도록 바뀌었을 뿐이다.

### 화면

해당 없음 — 응답 JSON이 기존과 동일해 프론트 연동·Orval 재생성이 필요 없다.

### 스키마 변경

해당 없음 — 기존 컬럼만 다르게 SELECT할 뿐 DDL 변경이 없다.

## 사람이 고른 선택

### 1. 프로젝션 도입 자체와 대상 8개 필드

- **물었던 것**: Discussion #549(코드 리뷰가 아니라 회의록)에서 이미 사람이 검토·확인한 내용을
  구현으로 옮기는 이슈라, 이 단계에서 새로 물을 것은 없었다. 다만 논의 과정에서 사람이 이 대화에서
  discussion 내용을 직접 검증(JFR 스택, before/after 수치, 코드 위치)한 뒤 "이슈를 생성해달라"고
  명시적으로 확정했다.
- **선택지**: (A) 엔터티 그대로 두고 `readOnly=true`만 유지 — 더티 체킹 스냅샷 비용만 빠지고
  컬럼 추출·엔티티 인스턴스화·영속성 컨텍스트 등록 비용은 그대로 남음 / (B) 프로젝션으로 전환 —
  기존 `InProgressDelivery` 패턴 재사용, 새 라이브러리 없음.
- **고른 것**: (B)
- **근거**: discussion #549가 로컬 before/after 재측정으로 Hibernate/ORM 비중 -12.1%p를 실측
  확인했고, 이 조회가 읽기 전용임을 grep으로 코드 근거까지 확보했다.
- **영향**: `DeliveryOrderRepository`의 두 조회 메서드와 `RiderDeliveryRequestService`의 호출부
  타입이 `DeliveryOrder` → `WaitingDeliverySummary`로 바뀐다. 같은 서비스의 다른 메서드
  (`getDeliveryRequest`, `acceptDeliveryRequest`)는 `findById` 기반이라 영향 없음.

## 스스로 판단한 것

- **`pickup`/`destination`(`@Embeddable Address`)을 프로젝션 인터페이스에서 평탄화한 이유**:
  `findWaitingOrdersWithinBoundingBox`가 네이티브 쿼리라 `ResultSet` 컬럼 라벨 ↔ 게터 이름의
  완전히 평평한 1:1 매칭만 지원하고, `getPickup(): Address`처럼 여러 컬럼을 중첩 객체로 묶어
  반환하도록 지시할 방법이 없다. 두 조회 메서드(`findByStatus`, `findWaitingOrdersWithinBoundingBox`)가
  같은 타입을 반환해야 서비스 호출부가 갈라지지 않으므로, 파생 쿼리 쪽도 같은 평탄화 규약을
  따랐다 — `WaitingDeliverySummary`에 `getPickup()` 대신 `getPickupRoadAddress()`/
  `getPickupLatitude()`/`getPickupLongitude()`를 직접 선언.
- **`findByStatus`가 파생 쿼리인 채로 임베더블 평탄화 프로젝션을 자동으로 SELECT 축소해줄지가
  이 이슈의 유일한 기술적 리스크였다.** discussion #549는 "쿼리 문자열은 그대로 — 스프링 데이터가
  자동으로 SELECT 절을 좁혀준다"고만 서술했고, Spring Data 문서상 임베디드 프로퍼티까지 이 자동
  최적화가 확정적으로 적용되는지는 명확하지 않았다. 사람에게 묻는 대신 통합 테스트로 직접
  검증하기로 하고(`shouldPopulateAllSummaryFieldsViaFindByStatusProjection`), 실제 MySQL로
  돌려 `pickupRoadAddress`·`pickupLatitude`·`pickupLongitude`·`destinationRoadAddress` 모두
  올바른 값으로 채워짐을 확인했다 — 예상대로 동작해 별도 JPQL `@Query`로 바꿀 필요가 없었다.
- **네이티브 쿼리(`findWaitingOrdersWithinBoundingBox`)의 컬럼 별칭**: `SELECT * FROM
  delivery_order ...`를 `SELECT order_id AS id, item_type AS itemType, pickup_road_address
  AS pickupRoadAddress, pickup_latitude AS pickupLatitude, pickup_longitude AS
  pickupLongitude, destination_road_address AS destinationRoadAddress, straight_distance_meters
  AS straightDistanceMeters, requested_at AS requestedAt FROM ...`로 바꿨다. `FORCE INDEX`·
  `WHERE` 절은 그대로 유지(#380 실측 근거는 이 변경과 무관).
- **`straightDistanceMeters`를 `int`가 아니라 `Integer`로 선언**: DTO(`RiderDeliveryRequestSummaryResponse`)
  필드 타입과 맞췄다. `DeliveryOrder` 엔터티는 원시 `int`를 쓰지만, 프로젝션은 어차피 DTO로
  바로 흘러들어가는 값이라 그쪽 타입에 맞추는 게 자연스럽다고 판단했다.

## 일부러 하지 않은 것

- **프로덕션 재프로파일링(JFR)은 이번 작업 범위에서 하지 않았다** — discussion #549 6장이 이미
  "로컬 실험만으로는 배포 환경 절감폭을 확정할 수 없다"고 명시했고, 이슈 #559의 완료 조건에도
  "병합·배포 후 재프로파일링"이 별도 항목으로 남아 있다. 이번 PR은 로컬 통합·E2E 테스트로 정확성만
  검증했고, 실제 CPU 절감폭 재확인은 배포 이후의 후속 작업이다.
- **`InProgressDelivery`처럼 상태(enum)를 `String`으로 받는 방어적 캐스팅은 하지 않았다** — 그
  패턴은 네이티브 쿼리가 enum 컬럼을 직접 SELECT할 때 필요한 것이고, 이번 프로젝션은 `status`
  컬럼 자체를 아예 SELECT하지 않는다(WHERE 조건으로만 쓰고 응답에 안 실림).

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderDeliveryRequestServiceTest` | 기존 35개 케이스 전부, 리포지토리 스텁 리턴 타입을 `WaitingDeliverySummary` 픽스처(테스트 전용 record)로 교체 |
| 통합 | `RiderDeliveryRequestServiceIntegrationTest` | 신규 2건 — `findByStatus`·`findWaitingOrdersWithinBoundingBox` 두 경로 모두 실제 MySQL에서 `pickupRoadAddress`/`pickupLatitude`/`pickupLongitude`/`destinationRoadAddress`/`itemType`/`straightDistanceMeters`/`requestedAt`이 정확히 채워지는지 검증(임베더블 평탄화 매핑의 핵심 리스크) |
| E2E | `RiderDeliveryRequestE2ETest` | 응답 스펙이 그대로임을 재확인(수정 없이 통과) |

실행 결과:

```text
./gradlew test --tests '*RiderDeliveryRequestServiceTest'          → BUILD SUCCESSFUL, 35 tests, 0 failures
./gradlew test --tests '*RiderDeliveryRequestServiceIntegrationTest*' → BUILD SUCCESSFUL, 17 tests, 0 failures
./gradlew test --tests '*RiderDeliveryRequestE2ETest*'             → BUILD SUCCESSFUL (수정 없이 통과)
./gradlew test (전체)                                               → BUILD SUCCESSFUL, 677 tests, 0 failures/0 errors
```

통합 테스트 첫 실행에서 신규 2건이 `requestedAt` 비교로 실패했다 — MySQL `DATETIME` 컬럼의
밀리초 정밀도가 자바 `LocalDateTime.now()`의 마이크로초 정밀도보다 낮아 마지막 자릿수가 갈리는,
프로젝션과 무관한 통상적인 라운드트립 정밀도 문제였다. `isEqualTo` 대신 `isCloseTo(..., within(1,
ChronoUnit.SECONDS))`로 바꿔 해결했다 — 이 자체가 "필드가 null로 조용히 매핑됐는지"에 대한 답은
아니었고, 값이 정확히(초 단위까지) 채워졌다는 걸 재확인해 원래 검증 목적은 그대로 달성했다.

### 검증하지 못한 것

- 실제 프로덕션 CPU 절감폭(위 "일부러 하지 않은 것" 참고 — 배포 후 재프로파일링 필요).

## 새로 생긴 미결 사항

- 없음. 이슈 #559의 완료 조건에 이미 있던 "병합·배포 후 JFR 재프로파일링" 항목만 그대로 남는다
  (`CLAUDE.md`에 새로 추가할 항목 없음 — 기존 이슈 완료 조건으로 이미 추적 중).
