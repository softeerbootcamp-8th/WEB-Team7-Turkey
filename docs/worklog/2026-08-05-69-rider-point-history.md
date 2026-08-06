# 라이더 정산·출금 내역 조회 작업 기록

- 이슈: [#69](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/69)
- 브랜치: `feature/69-rider-point-history`
- 범위: backend
- 작성일: 2026-08-05

## 무엇을 만들었나

`RiderPointApi.getPointTransactions`(이미 문서화·스텁만 있던 계약)의 구현체를 채웠다.
`point_transaction` 원장을 페이지네이션 + 유형 필터로 조회한다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/rider/points/transactions` | 정산·출금 내역(원장) 페이지 조회 | 400 잘못된 페이지, 500 지갑 없음 |

### 화면

해당 없음 — 프론트(`rider/_authed/points`)는 `useGetRiderPointTransactions`를 이미 소비하도록
연결돼 있었다(#219).

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 이슈 제목("정산·출금 내역 조회")과 실제 구현 대상의 불일치

- **물었던 것**: 사람에게 묻지 않고 근거로 판단했다 — `RiderPaymentController`에는 `getSettlements`
  (정산 전용), `getPointTransactions`(통합 원장), `getWithdrawals`(출금 전용) 세 스텁이 이미
  있었는데, 이슈 제목만 보면 셋 중 무엇을 가리키는지 모호했다.
- **판단 근거**: 프론트 `-useRiderPoints.ts`가 `useGetRiderPointTransactions` **하나만** 소비하고
  있었고, `pointFilterOptions`가 SETTLEMENT/WITHDRAWAL/WITHDRAWAL_REFUND 세 유형만 노출한다.
  `RiderPointApi` 문서에서 `getSettlements`는 "운행 기록 화면(/api/rider/history)"용,
  `getWithdrawals`는 출금 요청 상세(#103)류로 이미 용도가 갈려 있었다.
- **고른 것**: `getPointTransactions`만 구현. `getSettlements`·`getWithdrawals`는 그대로
  `return null` 스텁으로 남긴다.
- **영향**: 이슈 비고의 "정산 내역은 주문 ID를 참조한다"는 이 구현에서 곧이곧대로 성립하지
  않는다 — `PointTransaction.forSettlement`는 `deliveryOrder`가 아니라 `riderSettlement` FK만
  채운다(`ck_point_transaction_source`). 응답의 `deliveryId`는 SETTLEMENT 행에서 항상 null이고
  `settlementId`만 채워진다. 프론트가 이 필드들을 아직 쓰지 않아 문제되지 않지만, 나중에
  "정산 내역에서 배송 상세로 이동" 같은 요구가 생기면 `RiderSettlement.deliveryOrder`를 한 단계
  더 조인해야 한다.

## 스스로 판단한 것

- **페이지 검증**: `PageRequest.of`가 던지는 `IllegalArgumentException`을 그대로 흘려보낸다
  (`DeliveryListQueryService`와 같은 패턴). 별도 검증 코드를 추가하지 않았다.
- **`balance` 필드**: 목록의 마지막 항목 `balanceAfter`가 아니라 지갑의 현재 잔액을 그대로
  반환한다 — 필터·페이지에 따라 둘이 달라질 수 있어서다(기존 `PointTransactionListResponse`
  주석과 동일한 판단).
- **지갑 없음 → 500**: `getPointBalance`(#67)와 같은 판단을 그대로 따랐다.

## 일부러 하지 않은 것

- **`getSettlements`(정산 전용 목록)**: `/api/rider/history` 등 다른 화면 몫으로 보이나 그 이슈를
  특정하지 못했다. 후속: 미등록(필요해지면 별도 이슈에서).
- **`getWithdrawals`(출금 요청 목록, 상태 포함)**: #103(출금 요청 상세 조회)과 겹치는 영역으로
  보여 이번에 함께 만들지 않았다.
- **테스트 코드 작성**: 사용자 지시(`CLAUDE.local.md` §8)에 따라 작성하지 않았다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | 해당 없음 | 사용자 지시로 신규 작성 안 함 |
| 통합 | 해당 없음 | 상동 |
| E2E | 해당 없음 | 상동 |

실행 결과: 컴파일 검증은 사용자 요청으로 생략했다(직접 확인 예정).

### 검증하지 못한 것

- 컴파일·타입 확인, 실제 HTTP 응답(200/400/500) 확인 전부 하지 않았다.
- 정산·출금 혼합 목록이 실제로 최신순으로 나오는지, 유형 필터가 정확히 걸리는지 DB로 확인하지 못했다.

## 새로 생긴 미결 사항

- `getSettlements`·`getWithdrawals`가 어느 화면·이슈 몫인지 아직 특정되지 않았다(`RiderPointApi`
  문서상 의도만 있고 담당 이슈가 명시돼 있지 않음). `CLAUDE.md` 「확인이 필요한 항목」에 반영 필요.
