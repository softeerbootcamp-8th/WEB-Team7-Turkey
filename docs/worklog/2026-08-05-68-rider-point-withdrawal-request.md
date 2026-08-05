# 라이더 포인트 출금 요청 작업 기록

- 이슈: [#68](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/68)
- 브랜치: `feature/68-rider-point-withdrawal-request`
- 범위: backend
- 작성일: 2026-08-05

## 무엇을 만들었나

`RiderPointApi.requestWithdrawal` 계약(이미 문서화돼 있었음, #67 작업 중 함께 정의됨)의 구현체를
채웠다. 도메인(`RiderWithdrawal`, `RiderPayoutAccount`, `PointTransaction.forWithdrawal`)과 스키마
(V7, V11)는 이미 존재했고 이번에 실제로 호출하는 서비스 로직과 리포지토리 2개를 추가했다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| POST | `/api/rider/points/withdrawals` | 출금 요청(선차감 + WITHDRAWAL 원장) | 400 최소금액 미달, 409 계좌미등록/잔액부족/동시재전송 |

### 화면

해당 없음 — 프론트(`rider/_authed/points`)는 이미 Orval 훅과 `WithdrawalDialog`까지 연결돼
백엔드만 기다리고 있었다(#219 FE-RIDE-POINT-001에서 먼저 작업됨).

### 스키마 변경

해당 없음 — `rider_withdrawal`(V11)·`rider_payout_account`(V7)·`point_transaction`(V17) 모두
기존 마이그레이션 그대로 쓴다.

## 사람이 고른 선택

### 1. 출금 최소 금액

- **물었던 것**: 이슈의 "최소 출금 금액 미달" 예외를 처리하려면 구체적 하한값이 필요한데
  코드베이스에 선례가 없었다.
- **선택지**:
  - (A) 10,000P — 이체 건수를 더 줄임
  - (B) 1,000P — 충전 정책(#32)과 단위를 맞춤
  - (C) 5,000P
  - (D) 제한 없음(양수만 허용, DB `ck_rider_withdrawal_amount`로 충분)
- **고른 것**: 5,000P
- **근거**: 사람이 직접 지정함("5000포인트로 해").
- **영향**: `RiderPaymentService.MIN_WITHDRAWAL_AMOUNT = 5_000L`. 충전 최소단위(1,000원, #32)와는
  별개 상수라 서로 바뀌어도 영향 없음. `CLAUDE.md` 충전 정책처럼 이 값도 잠정값 취급 — 화면
  프리셋이 생기면 그때 같이 재검토.

### 2. 선행 이슈(#87 출금 계좌 등록) 미완료 상태에서 진행

- **물었던 것**: 명시적으로 사람에게 묻지는 않고, 프로젝트 보드 상태(#68 In progress, #87 Backlog)로
  판단해 그대로 진행했다.
- **선택지**:
  - (A) #68만 먼저 진행 — 조회용 리포지토리만 추가, 등록 API는 #87에서
  - (B) #87부터 구현
- **고른 것**: (A)
- **근거**: 보드에서 팀이 이미 #68을 In progress로, #87을 Backlog로 둬 이 순서를 의도한 것으로
  판단함. 도메인(`RiderPayoutAccount`)과 테스트는 이미 있어 #68 구현 자체는 계좌 등록 여부와
  무관하게 성립한다.
- **영향**: #87이 머지되기 전까지는 실제 운영 환경에서 이 API가 항상 409(계좌 미등록)를 반환한다.
  통합 테스트를 쓴다면 `RiderPayoutAccountRepository.save()`로 계좌를 직접 만들어 우회해야 한다.

## 스스로 판단한 것

- **출금 결과(성공/실패) 처리 미포함**: `RiderWithdrawal.complete()`/`fail()`을 이 서비스에서
  호출하지 않았다 — 별도 이슈(#90, RIDE-POINT-006 "모의 출금 결과 처리 및 실패 포인트 복구")가
  이미 있어 그 이슈 몫으로 남긴다. 응답은 항상 PENDING.
- **잠금 순서**: 이 트랜잭션은 `point_charge`를 건드리지 않으므로 `point_charge → point_wallet`
  잠금 순서 규칙과 무관하다. 지갑 하나만 `findByMemberIdForUpdate`로 잠근다(고객 `payForOrder`와
  같은 방식).
- **동시 재전송 처리**: 고객 `chargePointRequest`와 같은 패턴 — 조회로 순차 재전송을 흡수하고,
  `saveAndFlush` + `DataIntegrityViolationException` catch로 진짜 동시 재전송(`uk_rider_withdrawal_request`
  위반)을 409로 바꾼다.
- **`RiderPayoutAccountRepository`를 최소로 추가**: `findByRiderId`(사실상 `findById`의 별칭) 하나만
  두었다. 계좌 등록·변경 메서드는 만들지 않았다 — 그건 #87의 책임이고, 지금 추가하면 그 이슈가
  나중에 다시 설계해야 한다.

## 일부러 하지 않은 것

- **출금 계좌 등록/변경 API**: #87(Backlog)에서 구현 예정.
- **출금 성공/실패 모의 처리, 실패 시 포인트 복구**: #90(Backlog)에서 구현 예정. 이번 커밋은
  `RiderWithdrawal`을 PENDING으로 생성하는 데서 끝난다.
- **출금/정산/거래 내역 조회 API 구현**(`getSettlements`, `getPointTransactions`, `getWithdrawals`):
  `RiderPaymentController`에 여전히 `return null` 스텁으로 남아 있다. #69(RIDE-POINT-004) 범위.
- **테스트 코드 작성**: 사용자 지시(`CLAUDE.local.md` §8, 2026-07-28)에 따라 새 테스트를 작성하지
  않았다. 기존 `RiderPaymentServiceTest`는 생성자 시그니처 변경(3개 리포지토리 추가)으로 컴파일이
  깨져 있던 것만 최소로 고쳤다(모킹 인자 추가, 새 테스트 케이스는 추가하지 않음).

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | 해당 없음 | 사용자 지시로 신규 작성 안 함 |
| 통합 | 해당 없음 | 상동 |
| E2E | 해당 없음 | 상동 |

실행 결과:

```text
./gradlew compileJava → BUILD SUCCESSFUL
./gradlew compileTestJava → BUILD SUCCESSFUL (RiderPaymentServiceTest 생성자 호출부 수정 후)
./gradlew test --tests 'com.turkey.quick.payment.*' --tests 'com.turkey.quick.rider.domain.*' → BUILD SUCCESSFUL
```

### 검증하지 못한 것

- 실제 HTTP 요청으로 201/409/400 응답 코드를 확인하지 못했다(E2E 미작성).
- 동시 재전송 시 `uk_rider_withdrawal_request` 위반이 실제로 409로 잡히는지 실제 DB 경쟁으로
  검증하지 못했다(코드 경로는 `CustomerPaymentService.chargePointRequest`와 동일한 패턴).
- 계좌 등록 API가 없어 실제 등록된 계좌로 출금이 성공(201)하는 흐름을 수동으로도 확인하지
  못했다 — 테스트 데이터를 DB에 직접 넣어야 재현 가능.

## 새로 생긴 미결 사항

- 출금 최소 금액 5,000P는 화면 프리셋이나 은행 이체 수수료 정책이 확정되기 전까지는 잠정값이다
  (충전 최소 단위 1,000원과 같은 성격 — `CLAUDE.md` 「확인이 필요한 항목」의 충전 금액 정책 항목
  옆에 같은 취지로 추가해 둘 것).
