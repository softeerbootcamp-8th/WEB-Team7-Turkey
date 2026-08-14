---
title: Turkey ERD
status: draft
updated_at: 2026-07-28
owner: WEB-Team7-Turkey
---

# ERD

이 문서는 테이블의 책임과 관계를 설명한다. 실제 컬럼, 타입, 인덱스 및 제약조건은 MySQL 8.4 DDL을 최종 기준으로 한다.

## 1. 핵심 엔터티

고객/라이더를 별도 테이블로 나누지 않고 `member`를 역할 컬럼(`CUSTOMER`/`RIDER`)으로 구분한다. 라이더만 갖는 확장 정보는 `rider_profile`/`rider_payout_account`에 둔다. 컬럼 단위 상세와 예시 데이터는 [`docs/03-erd-reference.md`](03-erd-reference.md)를 참고한다.

| 엔터티 | 책임 |
|---|---|
| `member` | 로그인 계정, 역할(`CUSTOMER`/`RIDER`)과 공통 사용자 정보 |
| `rider_profile` | 라이더 역할 회원의 운행 상태 확장 정보(`member`와 1:1) |
| `rider_payout_account` | 라이더 정산 계좌 정보(`rider_profile`과 1:1) |
| `term` | 버전·대상 역할별 약관 |
| `member_term_agreement` | 회원과 약관 사이의 동의 이력 |
| `delivery_order` | 배송요청, 주소, 요금과 진행 상태. 배차 결과(`assigned_rider_id`)를 별도 테이블 없이 FK로 직접 보관한다 |
| `order_status_history` | 배송 상태 변화를 낱개로 기록하는 이력 |
| `fare_policy` | 버전 단위로 관리하는 기본요금·거리요금 정책 |
| `item_type_surcharge` | 요금 정책에 종속된 품목별 추가요금 |
| `order_fare_snapshot` | 주문 시점에 적용된 정책과 계산 결과(예상/확정) 스냅샷 |
| `delivery_proof` | 배송 완료 인증 정보(주문당 최대 1건) |
| `rider_location_history` | 선별 보관하는 라이더 위치 이력 |
| `point_wallet` | 회원의 현재 포인트 잔액 |
| `point_charge` | 고객의 포인트 충전 결제 트랜잭션 |
| `point_transaction` | 포인트 차감·충전·정산·출금 원장 |
| `rider_settlement` | 완료 배송에 대한 라이더 정산 |
| `rider_withdrawal` | 라이더의 정산 포인트 출금 신청 |
| `member_notification`(미구현) | 회원 알림 로그. 2·3차 MVP 이후 구현 예정이며 현재 코드·DDL에는 없다 |

Redis에 저장하는 세션과 최신 위치는 관계형 ERD의 원본 엔터티로 보지 않는다.

## 2. 관계

```text
Member 1 ── 0..1 RiderProfile
RiderProfile 1 ── 0..1 RiderPayoutAccount
Member 1 ── N DeliveryOrder (customer_id)
RiderProfile 1 ── N DeliveryOrder (assigned_rider_id)
DeliveryOrder 1 ── N OrderStatusHistory
Member 1 ── N OrderStatusHistory (actor_member_id, 0..1)
FarePolicy 1 ── N ItemTypeSurcharge
FarePolicy 1 ── N OrderFareSnapshot
DeliveryOrder 1 ── N OrderFareSnapshot
DeliveryOrder 1 ── 0..1 DeliveryProof
RiderProfile 1 ── N DeliveryProof
DeliveryOrder 1 ── N RiderLocationHistory
RiderProfile 1 ── N RiderLocationHistory
DeliveryOrder 1 ── 0..1 RiderSettlement
RiderProfile 1 ── N RiderSettlement
OrderFareSnapshot 1 ── 0..1 RiderSettlement
Member 1 ── 1 PointWallet
Member 1 ── N PointCharge
PointWallet 1 ── N PointTransaction
DeliveryOrder 1 ── N PointTransaction
PointCharge 1 ── N PointTransaction
RiderWithdrawal 1 ── N PointTransaction
RiderSettlement 1 ── 0..1 PointTransaction
RiderProfile 1 ── N RiderWithdrawal
Member 1 ── N MemberTermAgreement
Term 1 ── N MemberTermAgreement
```

## 3. 주요 무결성 규칙

- `member.role`은 `CUSTOMER`, `RIDER` 중 하나다.
- `rider_profile`은 `role='RIDER'`인 회원에게만 존재해야 한다(`CUSTOMER`는 별도 프로필 테이블 없이 `member` 자체가 프로필 역할을 한다).
- 배송요청에는 최대 하나의 활성 배차만 존재한다.
- 완료된 배송 하나에는 최대 하나의 정산만 존재한다.
- 포인트 원장은 금액, 유형, 대상 주문과 생성 시각을 보존한다.
- 상태 컬럼은 도메인에 정의된 값만 허용한다.
- 생성·수정 시각은 모든 핵심 테이블에 기록한다.

## 4. 애플리케이션과 DB 제약의 역할

MySQL 일반 제약조건만으로 다음 정책을 직접 표현하기 어려울 수 있다.

- 고객당 진행 중 주문 최대 1건
- 라이더당 진행 중 배송 최대 1건
- 상태별 조건부 유일성

따라서 다음을 조합한다.

1. 서비스 트랜잭션에서 현재 상태 검증
2. 조건부 업데이트 또는 잠금
3. 배차 및 정산에 대한 유일키
4. 동시성 통합 테스트

## 5. Redis 데이터 모델

```text
Session
key: session:{sessionId}
value: JSON string, {"memberId": ...} (#511, String + SET ... EX — 이전엔 Hash + HSET/EXPIRE 별도 호출)

Rider Current Location
key: rider:location:{riderId}
value: latitude, longitude, updatedAt

Rider GEO
key: riders:geo
member: riderId
coordinate: longitude, latitude

Phone Verification Code
key: phone-verification:code:{purpose}:{phoneNumber}
value: code
TTL: 5분

Phone Verification Cooldown
key: phone-verification:cooldown:{purpose}:{phoneNumber}
value: "1"
TTL: 60초

Phone Verification Attempts
key: phone-verification:attempts:{purpose}:{phoneNumber}
value: 오입력 횟수(정수, INCR)
TTL: 5분 (인증번호 코드와 동일 — 처음 증가할 때만 건다)

Phone Verification Verified Token
key: phone-verification:verified:{token}
value: "{purpose}:{phoneNumber}"
TTL: 10분
```

키 이름과 TTL은 구현 시 확정한다. (Phone Verification 6개 키는 #20~#21에서 확정, `docs/worklog/2026-07-28-20-phone-verification-request.md`,
`docs/worklog/2026-07-28-21-phone-verification-confirm.md` 참고)

## 6. 최종화 필요 항목

아래는 확정된 항목이다(참고로 남김):

- 데이터 접근 기술에 따른 테이블 매핑 전략 → JPA, 테이블과 엔터티 1:1 매핑
- 주소와 좌표의 컬럼 구조 → `delivery_order`에 `pickup_*`/`destination_*` 컬럼으로 직접 보관(도로명주소/상세주소/우편번호/위경도)
- 배차를 별도 테이블로 둘지 주문 FK로 단순화할지 → 주문 FK(`delivery_order.assigned_rider_id`)로 단순화, 별도 배차 테이블 없음
- 배송 완료 인증의 단건·다건 정책 → 단건(`delivery_proof`, 주문당 최대 1건)
- 포인트 잔액 캐시 컬럼 유지 여부 → 유지(`point_wallet.balance`)
- 논리 삭제 사용 범위 → `member.withdrawn_at`만 사용. 나머지 테이블은 소프트 삭제 없음(대부분 append-only 이력 테이블)

아직 미확정:

- 위치 이력 파티셔닝 및 인덱스(`rider_location_history`는 현재 파티셔닝 없는 단순 append-only 테이블)
