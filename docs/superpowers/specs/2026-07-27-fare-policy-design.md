# FarePolicy / ItemTypeSurcharge 도메인 엔터티 설계

- 관련 이슈: [#142](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/142) (Part of [#128](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/128) 1/5단계)
- 정본 DDL: GitHub Wiki "데이터베이스 물리적 설계" §5

## 배경 및 목적

이슈 #128(남은 도메인 엔터티 개발)을 5단계로 분리했다(#142~#146). 1단계인 이 문서는
`fare_policy` / `item_type_surcharge`의 엔터티·마이그레이션 설계를 다룬다.

이 단계를 가장 먼저 진행하는 이유:
- 다른 신규 엔터티에 의존하지 않는 독립 테이블
- `active_policy_marker` 생성 컬럼의 H2(MySQL 모드) 호환성을,
  2단계 `DeliveryOrder`가 쓸 생성 컬럼 2개(`active_customer_id`/`active_rider_id`)보다
  먼저 작고 리스크가 낮은 곳에서 검증 — 실증 결과 H2 2.3.232(MySQL 호환 모드)는
  `GENERATED ALWAYS AS (...) VIRTUAL`의 `VIRTUAL` 키워드를 파싱하지 못했다. MySQL 은
  생성 컬럼 기본값이 VIRTUAL 이므로, 키워드를 생략해도 운영(MySQL)·로컬(H2) 양쪽에서
  동일한 스키마가 만들어진다. 이 저장소는 `VIRTUAL` 키워드를 생략하는 방식을 채택했고,
  #143 도 동일하게 따른다.

범위는 **엔터티 + Flyway 마이그레이션 + 도메인 TDD**까지다. 요금 계산 서비스 로직,
정책 활성화 시 기존 활성 정책을 자동으로 비활성화하는 흐름 등은 이 이슈 범위 밖이며,
이후 서비스 계층 이슈에서 다룬다.

## 패키지 위치

`FarePolicy`, `FarePolicyStatus`, `ItemTypeSurcharge`, `ItemType` 모두
`com.turkey.quick.order.domain` 패키지에 둔다.

- 요금 정책은 결제(payment)보다 주문 요금 결정(order)에 더 가깝다고 판단
- `ItemType`은 2단계 `DeliveryOrder.item_type`에서도 그대로 재사용되므로,
  같은 패키지에 두는 것이 자연스럽다
- 기존 `OrderStatus.java`는 `order/` 탑 레벨에 있지만(과거 관례), 이번에 새로 만드는
  엔터티/enum들은 `member`·`rider`·`payment` 도메인의 기존 `{package}/domain/` 관례를 따른다

## 애그리거트 경계

`ItemTypeSurcharge`는 특정 `FarePolicy` 버전 없이는 존재 의미가 없는 하위 구성요소다
(정책 버전 하나를 정의할 때 물품별 할증도 함께 확정됨, 독립적인 조회/생성 케이스 없음).
따라서 `FarePolicy`를 애그리거트 루트로 두고 `@OneToMany(cascade = ALL, orphanRemoval = true)`로
소유하며, `ItemTypeSurcharge`의 생성자는 package-private으로 막아 `FarePolicy.addSurcharge()`를
통해서만 생성되도록 한다.

(참고: `MemberTermAgreement`처럼 서로 독립적인 두 루트를 잇는 로그성 테이블과는 성격이 달라
그 패턴을 그대로 가져오지 않았다.)

## 엔터티 설계

### FarePolicyStatus / ItemType

```java
public enum FarePolicyStatus { ACTIVE, INACTIVE }

public enum ItemType { DOCUMENT, SMALL_PARCEL, MEDIUM_PARCEL, LARGE_PARCEL, FOOD }
```

`ItemType` 값 목록은 문서에 사전 정의가 없어 이번에 직접 정했다(서류/소형·중형·대형 물품/음식).

### FarePolicy

주요 필드는 DDL과 1:1 대응(`policyVersion`, `baseFare`, `distanceUnitMeters`,
`distanceUnitFare`, `maxDeliveryDistanceMeters`, `status`, `effectiveFrom`, `effectiveTo`,
`createdAt`). DDL에 `updated_at` 컬럼이 없으므로 `@PreUpdate`는 두지 않는다.

행위 메서드:

- `static create(policyVersion, baseFare, distanceUnitMeters, distanceUnitFare, maxDeliveryDistanceMeters, effectiveFrom)`
  — 항상 `INACTIVE`로 시작. 금액·거리 값은 양수 검증(`IllegalArgumentException`).
- `addSurcharge(ItemType, long surchargeAmount)` — 음수 금액 거부, 같은 `itemType` 중복 추가 거부
  (`IllegalStateException`, DB `uk_item_surcharge_policy_type`과 동일 조합을 앱에서도 방어).
- `activate()` — `INACTIVE → ACTIVE`만 허용. 기존 활성 정책이 있는 상태에서 호출해도 이 엔터티는
  막지 않는다 — 동시 활성 정책이 2개가 되는 경쟁은 DB `uk_fare_policy_active`
  (`UNIQUE(active_policy_marker)`)가 최종 방어선이며, 기존 정책을 먼저 비활성화하는 오케스트레이션은
  서비스 계층 책임(범위 밖).
- `deactivate()` — `ACTIVE → INACTIVE`만 허용, `effectiveTo = now(UTC)`로 세팅해 적용 종료 시각을 남긴다.

`active_policy_marker` 생성 컬럼(`VIRTUAL` 키워드 생략, MySQL 기본값과 동일한 동작)은 JPA 필드로
매핑하지 않는다(DB 전용 제약 목적).

### ItemTypeSurcharge

`farePolicy`(`@ManyToOne(LAZY)`), `itemType`, `surchargeAmount`, `createdAt`. DDL에
`updated_at` 컬럼이 없으므로 `@PreUpdate` 없음. package-private `static create(FarePolicy, ItemType, long)`만
제공하고 public 생성 경로는 없음.

## 마이그레이션

- `V8__create_fare_policy.sql`
- `V9__create_item_type_surcharge.sql`

DDL(Wiki "데이터베이스 물리적 설계" §5 및 사용자 제공 상세 DDL)을 그대로 반영하고,
기존 마이그레이션과 동일한 명명 규칙(`pk_`/`uk_`/`fk_`/`ck_`)을 따른다.

## 테스트 계획

`FarePolicyTest`(`PointChargeTest` 스타일, TDD RED→GREEN):

- 생성 시 `INACTIVE`, `effectiveTo` null, `surcharges` 빈 컬렉션
- 금액/거리 값 0 이하 시 `IllegalArgumentException`
- `activate()`: `INACTIVE→ACTIVE` 성공 / 이미 `ACTIVE`면 `IllegalStateException`
- `deactivate()`: `ACTIVE→INACTIVE` 성공 + `effectiveTo` 설정 / `INACTIVE`에서 호출 시 예외
- `addSurcharge()`: 정상 추가 / 중복 `itemType` 예외 / 음수 금액 예외

이후 로컬 H2(MySQL 모드) 부팅으로 Flyway V1~V9 적용 + Hibernate `validate` 통과를 실증한다
(`active_policy_marker` 생성 컬럼 호환성 검증이 핵심 목표 — 결과: `VIRTUAL` 키워드가 있으면 H2가
파싱하지 못해 실패하므로, 키워드를 생략해 MySQL과 동일한 기본 동작을 유지하면서 H2 호환성을 확보했다).

## 완료 조건

- [ ] `FarePolicyStatus`, `ItemType`, `FarePolicy`, `ItemTypeSurcharge` 구현
- [ ] `V8__create_fare_policy.sql`, `V9__create_item_type_surcharge.sql` 작성
- [ ] `FarePolicyTest` TDD로 작성 및 통과
- [ ] 로컬 H2 부팅으로 Flyway 적용 + Hibernate `validate` 통과 실증
- [ ] CI 테스트 통과
- [ ] 코드 리뷰 반영
