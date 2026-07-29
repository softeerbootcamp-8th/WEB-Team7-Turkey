---
title: Turkey 도메인 정책
status: draft
updated_at: 2026-07-23
owner: WEB-Team7-Turkey
---

# 도메인 정책

## 1. 계정과 역할

- 계정은 `CUSTOMER` 또는 `RIDER` 중 하나의 역할만 가진다.
- 하나의 계정이 두 역할을 동시에 수행하지 않는다.
- 고객과 라이더 전용 기능은 서버에서 역할을 검증한다.

## 2. 고객의 진행 중 주문 제한

고객은 동시에 여러 개의 진행 중 배송요청을 가질 수 없다.

```text
진행 중 상태
WAITING
ASSIGNED
MOVING_TO_PICKUP
PICKED_UP
DELIVERING
```

`COMPLETED` 또는 `CANCELED` 주문만 존재하는 고객은 새 배송요청을 생성할 수 있다.
전체 이력 관계는 `Customer 1 : N DeliveryOrder`를 유지한다.

## 3. 라이더 상태

| 상태 | 의미 |
|---|---|
| `UNAVAILABLE` | 운행 종료 또는 로그아웃 상태 |
| `AVAILABLE` | 배차를 받을 수 있는 상태 |
| `BUSY` | 배송을 수행 중인 상태 |

### 허용 전이

```text
UNAVAILABLE → AVAILABLE : 운행 시작
AVAILABLE → UNAVAILABLE : 운행 종료 또는 로그아웃
AVAILABLE → BUSY        : 배차 확정
BUSY → AVAILABLE        : 배송 완료
```

- `BUSY` 상태에서는 운행 종료할 수 없다.
- 라이더는 동시에 진행 중 배송을 최대 1건만 수행한다.

## 4. 배송 상태

| 상태 | 의미 |
|---|---|
| `WAITING` | 배차 대기 |
| `ASSIGNED` | 라이더 배차 완료 |
| `MOVING_TO_PICKUP` | 픽업지 이동 중 |
| `PICKED_UP` | 물품 인수 완료 |
| `DELIVERING` | 목적지 배송 중 |
| `COMPLETED` | 배송 완료 |
| `CANCELED` | 배송 취소 |

### 정상 전이

```text
WAITING → ASSIGNED
ASSIGNED → MOVING_TO_PICKUP
MOVING_TO_PICKUP → PICKED_UP
PICKED_UP → DELIVERING
DELIVERING → COMPLETED
WAITING → CANCELED
```

- 상태를 요청 값으로 직접 덮어쓰지 않는다.
- 현재 상태와 수행 행위를 기준으로 전이를 검증한다.
- 정의되지 않은 전이는 거부한다.

## 5. 배차 정책

배차 확정은 하나의 트랜잭션에서 처리한다.

```text
배송: WAITING → ASSIGNED
라이더: AVAILABLE → BUSY
배차 관계 생성 또는 담당 라이더 기록
```

보장해야 하는 조건은 다음과 같다.

- 하나의 배송요청에는 최대 한 명의 라이더만 배정된다.
- 하나의 라이더는 동시에 최대 한 건의 진행 중 배송만 담당한다.
- 경쟁에서 실패한 수락 요청은 명확한 실패 결과를 받는다.
- 일부 상태만 변경되는 부분 성공을 허용하지 않는다.
- 동일 요청 재전송에 대한 정책은 API 멱등성 정책으로 확정한다.

동시성 구현 방식은 ADR에서 결정한다.

## 6. 취소 정책

- 고객의 일반 취소는 `WAITING → CANCELED`만 허용한다.
- 배차 이후 취소는 MVP 일반 기능에서 제외한다.
- 취소 시 포인트 환불 여부와 시점은 결제 정책 결정 후 확정한다.

## 7. 위치 정책

- 라이더의 최신 위치는 Redis에 저장한다.
- 주변 라이더 검색은 Redis GEO를 사용한다.
- MySQL에는 장기 보관이 필요한 위치 이력만 선별 저장한다.
- 위치 이력 저장 주기는 시간, 이동 거리 또는 상태 변화 기준 중에서 결정한다.
- 웹 클라이언트는 포그라운드 위치 수집을 전제로 한다.

## 8. SSE 정책

- 고객은 본인 배송요청의 위치만 구독할 수 있다.
- 연결 생성 시 세션과 배송 조회 권한을 검증한다.
- 위치가 유의미하게 변경된 경우에만 이벤트를 전송한다.
- 완료 또는 취소 시 해당 배송의 연결을 종료한다.
- 타임아웃, heartbeat, 재연결과 중복 연결 정책은 ADR에서 확정한다.
- **어느 인스턴스가 이벤트를 전달하는가**도 ADR에서 확정한다(2026-07-29 수평 확장 요구사항 변경).
  위치를 처리한 인스턴스와 고객의 SSE 연결을 들고 있는 인스턴스가 다를 수 있다
  ([Discussion #246](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/discussions/246)).
- 중복 연결 수 제한은 **인스턴스별로 셀 수 없다** — 같은 주문의 연결이 여러 인스턴스에 흩어지므로
  공유 저장소(Redis) 기준으로 세야 한다.

## 9. 포인트 정책

MVP에서는 실제 PG 연동을 하지 않는다.

결정해야 할 항목:

- 주문 생성 시 선차감 또는 별도 결제 승인 모킹
- 취소 시 전액 환불 여부
- 예상 요금과 최종 요금 차이 허용 여부
- 잔액과 원장 갱신의 원자성
- 중복 차감 방지 방식

포인트 잔액은 원장과 불일치하지 않아야 한다.

## 10. 배송 완료와 정산

배송 완료는 다음 변경을 하나의 논리적 작업으로 처리한다.

```text
배송: DELIVERING → COMPLETED
라이더: BUSY → AVAILABLE
정산 내역 생성
```

- 일부 작업만 반영되어서는 안 된다.
- 동일 배송에 정산 내역은 중복 생성되지 않아야 한다.
- 정산 생성 시점과 재처리 정책은 별도 ADR에서 확정한다.
