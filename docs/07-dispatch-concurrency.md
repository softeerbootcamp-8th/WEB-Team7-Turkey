# 배차 동시성 구현 가이드

배차 확정 시 "한 주문 → 정확히 한 라이더"와 "한 라이더 → 진행 중 1건"을 락 인프라 없이 보장하기 위한 **구현 지침**이다.
결정 배경·대안 비교·근거는 위키 ADR을 정본으로 한다.

> 배경·근거: [ADR‐006 배차 동시성 처리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR%E2%80%90006-%EB%B0%B0%EC%B0%A8-%EB%8F%99%EC%8B%9C%EC%84%B1-%EC%B2%98%EB%A6%AC) · 데이터 접근 방침: [ADR‐005](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR%E2%80%90005:-%EB%8D%B0%EC%9D%B4%ED%84%B0-%EC%A0%91%EA%B7%BC-%EA%B8%B0%EC%88%A0%EB%A1%9C-JPA%EB%A5%BC-%EC%82%AC%EC%9A%A9%ED%95%98%EB%90%98-%EB%8F%99%EC%8B%9C%EC%84%B1-%EC%A7%80%EC%A0%90%EC%9D%80-SQL%EB%A1%9C-%EC%A7%81%EC%A0%91-%EC%B2%98%EB%A6%AC%ED%95%9C%EB%8B%A4)

## 결정 요약

**조건부 UPDATE(Compare-And-Set)를 채택한다.** 명시적 락(비관적/낙관적/분산/애플리케이션 락)을 관리하지 않고, DB의 조건부 UPDATE + 갱신 행 수 판정으로 처리한다. 추후 부하가 실측으로 문제되면 애플리케이션 락을 보조 필터로 검토한다.

## 구현 방식

배차 확정은 두 상태 변경(주문 `WAITING → ASSIGNED`, 라이더 `AVAILABLE → BUSY`)을 **한 트랜잭션**에서 처리한다. 각 변경은 "현재 상태를 WHERE 조건으로 건" 조건부 UPDATE로 수행하고, **갱신 행 수(0 또는 1)로 성공·실패를 판정**한다.

- 두 UPDATE를 하나의 트랜잭션 안에서 순서대로 실행하고 **둘 다 1행일 때만 커밋**한다. 하나라도 0행이면 예외를 던져 **전체를 롤백**한다. 이로써 "주문만 배차되고 라이더는 안 잡히는" 부분 성공을 막는다.

의사코드:

```text
트랜잭션 {
  a ← UPDATE 주문   SET ASSIGNED, 라이더, assigned_at   WHERE id=orderId AND status=WAITING
  if a == 0 : 실패 반환(현재 상태 재조회로 사유 구분)
  b ← UPDATE 라이더 SET BUSY                            WHERE id=riderId AND status=AVAILABLE
  if b == 0 : 예외 → 롤백(주문 점유도 함께 되돌아감)
  성공(커밋)
}
```

### 각 조건부가 막는 것

| 조건부 | 막는 것 |
|---|---|
| 주문 `status = WAITING` | 두 라이더 → 한 주문 (이중 배차) |
| 라이더 `operating_status = AVAILABLE` | 한 라이더 → 두 주문 (동시 2건) |

라이더 조건부의 본래 목적은 `AVAILABLE → BUSY` **전이 유효성**이며, "한 라이더 1건"의 정본 보장은 `delivery_order.active_rider_id` 생성 컬럼 + UNIQUE가 **구조적 백스톱**으로 맡는다.

### 실패 사유 구분

갱신 행 수 0은 사유를 알려주지 않으므로, 실패 시 현재 상태를 재조회해 구분해 반환한다.

| 결과 | 반환 |
|---|---|
| 주문이 이미 WAITING 아님 | `ALREADY_ASSIGNED` / `ORDER_CANCELED` |
| 라이더가 이미 AVAILABLE 아님 | `RIDER_NOT_AVAILABLE` |

## 잠금 순서 (데드락 회피)

배차·상태 전이 트랜잭션에서 여러 행(주문·라이더)을 갱신하면 교착이 발생할 수 있다. **모든 트랜잭션은 아래 순서로 행을 갱신한다.**

```
1) delivery_order   (주문)
2) rider_profile    (라이더)
3) 그 밖의 부속 테이블
```

- 위 배차 로직처럼 **주문 → 라이더** 순으로 UPDATE 문을 배치한다.
- 이 순서를 모든 관련 트랜잭션에서 **동일하게 고정**한다. 순서를 뒤집는 코드가 하나라도 생기면 데드락 위험이 생기므로 리뷰에서 확인한다.

## 검증

- 같은 주문을 N개 스레드가 동시 수락 → **성공 1건, 나머지 전부 명확한 실패(사유 구분)**.
- 한 라이더가 서로 다른 두 주문을 동시 수락 → **한 건만 배차, 다른 건 `RIDER_NOT_AVAILABLE`**.
- 배차 성공 시 주문 `ASSIGNED`와 라이더 `BUSY`가 **항상 함께** 반영(부분 성공 없음).
