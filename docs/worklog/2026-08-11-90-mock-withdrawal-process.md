# 모의 출금 결과 처리 및 실패 포인트 복구 작업 기록

- 이슈: [#90](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/90)
- 브랜치: `feature/90-mock-withdrawal-process`
- 범위: backend
- 작성일: 2026-08-11

## 무엇을 만들었나

`RiderWithdrawal`(#68)이 만들어 놓은 PENDING 출금 요청을 COMPLETED/FAILED 로 확정하는 처리
엔드포인트를 추가했다. 도메인의 `complete()`/`fail()`은 이미 있었고(#68 구현 시 함께 작성,
`RiderPaymentService.requestWithdrawal` 주석에 "#90 이 담당"이라고 명시돼 있었음), 이번 이슈는
그것을 호출하는 서비스·API 계층을 만드는 작업이다.

**1차 구현을 갈아엎었다**: 처음에는 요청 바디에 `success`/`failureReason`을 직접 받아 그대로
반영했는데, 사람이 "결제(PaymentGateway/MockPaymentGateway)처럼 외부 API 를 호출하는 것처럼
만들라"고 정정했다. `PaymentGateway`/`MockPaymentGateway`/`PointChargeApprover`와 같은 구조로
`PayoutGateway`/`MockPayoutGateway`/`WithdrawalProcessor`를 새로 만들고, 서비스는 파사드로
바꿔 트랜잭션 밖에서 게이트웨이를 호출한 뒤 결과를 확정 트랜잭션에 넘기는 흐름으로 다시 짰다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| POST | `/api/rider/points/withdrawals/{withdrawalId}/process` | 모의 은행 이체(PayoutGateway) 호출 결과 반영 + 실패 시 포인트 복구 | 404 없음/타인 것, 409 이미 처리됨, 502 이체 결과 불명(타임아웃) |

### 화면

해당 없음 — 이슈에 화면 언급 없음, 백엔드 계약만 구현.

### 스키마 변경

`V19__add_rider_withdrawal_provider_transfer_key.sql` — `rider_withdrawal.provider_transfer_key`
(VARCHAR(100), NULL, UNIQUE) 추가. `point_charge.provider_payment_key`와 같은 역할 —
확정 트랜잭션 전에 이체 식별자를 선커밋해 "이체는 됐는데 DB 반영 실패" 상황을 추적 가능하게 한다.
1차 구현에서는 이 컬럼 없이 로그로만 남겼다가, 사람이 "진행해"로 승인해 뒤늦게 추가했다.

## 사람이 고른 선택

### 1. 출금 처리를 결제 모킹과 같은 게이트웨이 구조로 만들 것

- **물었던 것**: (게이트에서 미리 묻지 않고, 1차 구현 리뷰에서 사람이 직접 정정) "결제처럼
  `PaymentGateway`를 만들어서 실제 외부 API 호출하는 것처럼 하라 — 지금은 그냥 서버가
  완료됐다고 하고 있다."
- **선택지**:
  - (A) 요청 바디에 `success`/`failureReason`을 직접 받아 그대로 도메인에 반영 — 구현은 간단하지만
    "성공/실패는 은행이 판단한다"는 결제 쪽 설계 원칙(`MockPaymentGateway` 주석)과 어긋나고,
    실 은행 API 연동 시 이 필드가 통째로 사라져야 해서 계약이 다시 바뀐다.
  - (B) `PayoutGateway`(포트) + `MockPayoutGateway`(모의 구현) + `WithdrawalProcessor`
    (확정 트랜잭션 전담, `PointChargeApprover`와 동일 역할)로 나누고, 서비스는 트랜잭션 밖에서
    게이트웨이를 호출한 뒤 결과를 짧은 트랜잭션에 반영 — 결제 쪽과 구조가 완전히 대칭이라
    실 은행 API로 교체할 때 `MockPayoutGateway` 하나만 갈아끼우면 된다.
- **고른 것**: (B)
- **근거**: 사람이 결제 모킹 구조를 명시적으로 지목하며 "지금은 그냥 서버가 완료됐다는 식으로
  하고 있다"고 지적 — 겉보기 API 계약(성공/실패 입력)이 아니라 내부 구조(외부 호출처럼 다루기)가
  이 이슈의 핵심 요구였다.
- **영향**: `WithdrawalProcessRequest`가 `success`/`failureReason` 대신 `authToken` 하나만 받는다
  (`PointChargeConfirmRequest`와 같은 모양). 모의 실패를 재현하려면
  `MockPayoutGateway.DECLINE_TOKEN`("mock_decline")을 `authToken`으로 보내야 한다. 실 은행 API
  연동 시 `PayoutGateway` 구현체만 교체하면 되고 `RiderPaymentService.processWithdrawal`의
  흐름(사전 검증 → 게이트웨이 호출 → 확정)은 그대로 유지된다.

## 스스로 판단한 것

- **`TIMEOUT`은 확정하지 않고 502 로 응답, PENDING 유지**: `confirmPointCharge`가 PG 타임아웃일 때
  FAILED 로 확정하지 않는 것과 같은 이유 — 이체가 실제로 성사됐는지 알 수 없는 상태에서 FAILED로
  확정하면 포인트를 이중으로 내주는 위험이 있다.
- **재처리를 멱등 응답이 아니라 409 로 거부**: `PointChargeApprover`는 이미 승인된 충전을 다시
  승인하려 하면 에러 없이 현재 상태를 돌려준다(멱등). 하지만 이슈 예외 처리 절에 "이미 완료된
  요청"이 명시적으로 나열돼 있어, 여기서는 재처리 자체를 클라이언트 오류로 다뤘다.
- **상태 재확인을 도메인 메서드 호출 전에 서비스/프로세서에서 먼저 함**: `RiderWithdrawal.complete()`/
  `fail()`도 PENDING 이 아니면 `IllegalStateException`을 던지지만, `GlobalExceptionHandler`가
  이를 400 으로 뭉갠다. 409 의미를 살리려면 `WithdrawalProcessor`에서 먼저 상태를 확인해
  `BusinessException(CONFLICT)`을 던져야 했다(`PointChargeApprover.finalizeApproval`과 같은 패턴).
- **소유자 확인을 조회 쿼리에 포함**: 사전 검증(`findByIdAndRider_MemberId`, 잠금 없음)과 확정
  단계(`findByIdAndRiderIdForUpdate`, 잠금)가 모두 소유자 조건을 쿼리에 넣어 "없음"과 "타인 것"을
  같은 404 로 응답한다 — #71 라이더 운행 기록 상세와 동일한 판단.
- **포인트 복구 잠금 순서를 rider_withdrawal → point_wallet 로 고정**: `PointChargeApprover`가
  point_charge → point_wallet 순서를 쓰는 것과 같은 이유(반대 순서로 잠그는 코드가 생기면 데드락).
- **`markTransferReceived`를 `RiderWithdrawal` 도메인 메서드로 추가**: `PointCharge.markApprovalReceived`
  와 같은 계약(PENDING 아니면 거부, 이미 다른 식별자가 있으면 덮어쓰지 않고 불일치만 알림)을
  그대로 따랐다 — 왜 다르게 할 이유를 찾지 못했다.
- **`MockPayoutGateway`의 성공/실패 판단 트리거로 `authToken`(DECLINE_TOKEN)을 재사용**: 결제는
  클라이언트가 결제창을 거쳐 돌아오며 `authToken`을 받아 오지만, 송금은 그런 왕복이 없다. 그래도
  같은 이름·같은 메커니즘(특정 토큰이면 거절)을 택한 이유는, 모의 처리 결과를 통제할 방법 자체는
  필요하고(QA·프론트 시뮬레이션 화면이 실패 케이스를 확인해야 함) 그 방법을 결제와 다르게 새로
  발명할 이유가 없기 때문이다.

## 일부러 하지 않은 것

- **`RiderPointApi.getWithdrawals`(출금 내역 목록, `return null` 스텁)를 함께 구현하지 않음**:
  이 이슈(#90)의 범위가 아니고, `CLAUDE.md`에도 "담당 화면·이슈 미정"으로 이미 남겨져 있다.
- **테스트 코드 작성 안 함**: `CLAUDE.local.md`의 사용자 지시("테스트 코드를 작성하지 않는다",
  2026-07-28)를 따랐다. 스킬 기본 절차(단위·통합·E2E)와 배치되지만 로컬 작업 지시가 우선한다.
  기존 `RiderPaymentServiceTest`는 생성자 시그니처 변경(`PayoutGateway`·`WithdrawalProcessor`
  추가) 때문에 컴파일이 깨져 mock 인자만 추가해 다시 컴파일되게 했다 — 새 테스트 케이스는
  추가하지 않았다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | (새로 작성 안 함) | - |
| 통합 | (없음) | - |
| E2E | (없음) | - |

실행 결과:

```text
./gradlew compileJava -q → 성공(경고 없음)
./gradlew compileTestJava -q → 성공(RiderPaymentServiceTest 생성자 인자만 보강, 새 테스트는 없음)
```

### 검증하지 못한 것

- 실제 동시 처리 요청 두 건이 하나만 반영되는지(WithdrawalProcessor 잠금 효과)는 통합 테스트로
  검증하지 못했다.
- **`bootRun`으로 Flyway/Hibernate validate 통과 여부를 직접 띄워 확인하지 않았다.** V19 마이그레이션과
  `RiderWithdrawal` 엔터티에 컬럼을 추가했으므로 `CLAUDE.local.md` §3 규칙상 `docker compose up -d`
  + `bootRun`으로 `Started QuickApplication` 로그를 직접 확인해야 하는 케이스인데, 로컬 인프라를
  직접 띄우지 않는다는 방침 때문에 이 세션에서는 못 했다. **병합 전 반드시 한 번 확인 필요.**

## 새로 생긴 미결 사항

- 출금 처리 결과를 실제로 누가/언제 호출할지(라이더 앱 화면 vs 운영 도구)가 이슈에 없어 정하지
  않았다. 화면이 필요해지면 별도 이슈로 다뤄야 한다.
