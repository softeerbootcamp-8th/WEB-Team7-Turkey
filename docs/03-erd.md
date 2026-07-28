---
title: Turkey ERD
status: draft
updated_at: 2026-07-23
owner: WEB-Team7-Turkey
---

# ERD

이 문서는 테이블의 책임과 관계를 설명한다. 실제 컬럼, 타입, 인덱스 및 제약조건은 MySQL 8.4 DDL을 최종 기준으로 한다.

## 1. 핵심 엔터티

| 엔터티 | 책임 |
|---|---|
| `member` | 로그인 계정, 역할과 공통 사용자 정보 |
| `customer` | 고객 전용 프로필 |
| `rider` | 라이더 전용 프로필과 운행 상태 |
| `delivery_order` | 배송요청, 주소, 요금과 진행 상태 |
| `delivery_assignment` | 주문과 라이더의 배차 결과 및 배차 시각 |
| `point_account` | 고객의 현재 포인트 잔액 |
| `point_transaction` | 포인트 차감·충전·환불 원장 |
| `settlement` | 완료 배송에 대한 라이더 정산 |
| `delivery_proof` | 배송 완료 인증 정보 |
| `rider_location_history` | 선별 보관하는 라이더 위치 이력 |

Redis에 저장하는 세션과 최신 위치는 관계형 ERD의 원본 엔터티로 보지 않는다.

## 2. 관계

```text
Member 1 ── 0..1 Customer
Member 1 ── 0..1 Rider
Customer 1 ── N DeliveryOrder
DeliveryOrder 1 ── 0..1 DeliveryAssignment
Rider 1 ── N DeliveryAssignment
Customer 1 ── 1 PointAccount
PointAccount 1 ── N PointTransaction
DeliveryOrder 1 ── N PointTransaction
DeliveryOrder 1 ── 0..1 Settlement
Rider 1 ── N Settlement
DeliveryOrder 1 ── 0..N DeliveryProof
Rider 1 ── N RiderLocationHistory
DeliveryOrder 1 ── 0..N RiderLocationHistory
```

## 3. 주요 무결성 규칙

- `member.role`은 `CUSTOMER`, `RIDER` 중 하나다.
- 회원 역할과 고객·라이더 프로필 유형이 일치해야 한다.
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
value: memberId, role, expiresAt

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

- 데이터 접근 기술에 따른 테이블 매핑 전략
- 주소와 좌표의 컬럼 구조
- 배차를 별도 테이블로 둘지 주문 FK로 단순화할지
- 배송 완료 인증의 단건·다건 정책
- 위치 이력 파티셔닝 및 인덱스
- 포인트 잔액 캐시 컬럼 유지 여부
- 논리 삭제 사용 범위
