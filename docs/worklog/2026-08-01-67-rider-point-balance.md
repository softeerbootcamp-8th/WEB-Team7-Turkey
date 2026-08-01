# 라이더 포인트 잔액 조회 작업 기록

- 이슈: [#67](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/67)
- 브랜치: `feature/67-ride-point-002-라이더-포인트-잔액-조회`
- 범위: backend
- 작성일: 2026-08-01

## 무엇을 만들었나

`RiderPointApi` / `RiderPaymentController` 는 이미 있었지만 `getPointBalance` 가 `null` 을 반환하는
껍데기였다. 이번에 서비스(`RiderPaymentService`)를 만들어 배선하고, 인증 인터셉터에 경로를 등록했다.
DTO(`PointBalanceResponse`)·리포지토리(`PointWalletRepository`)·엔터티(`PointWallet`)는 고객 잔액
조회(#31)가 만들어 둔 것을 그대로 쓴다 — 지갑은 `member` 와 PK 를 공유하는 회원 단위 개념이라
액터별로 스키마가 갈리지 않는다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/rider/points` | 로그인한 라이더의 출금 가능 포인트 잔액 | 401 미로그인·세션 만료·역할 불일치 / 500 지갑 없음 |

입력값 없음. `riderId` 는 세션(`AuthenticatedRider`)에서만 얻고 요청 파라미터·바디로 받지 않는다.

### 화면

해당 없음. 이슈에 화면이 없어 범위를 backend 로 잡았다. `frontend/src/api/generated/rider-point/`
훅은 인터페이스가 이미 있었으므로 예전에 생성돼 있고, 이번 변경으로 스펙이 바뀌지 않아
`pnpm generate:api` 를 다시 돌릴 필요가 없다.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 잔액 조회 서비스를 어디에 둘 것인가

- **물었던 것**: 고객과 로직이 완전히 같은데(`findByMemberId` → 응답 변환) 클래스를 나눌지, 공용으로 뽑을지
- **선택지**:
  - (A) `RiderPaymentService` 신설 — 고객과 대칭 구조 / 지금 이 순간은 2줄 중복
  - (B) 공용 `PointWalletQueryService` 추출 — 중복 없음 / `CustomerPaymentService` 도 함께 수정해야 하고, 인터셉터 선례와 반대 방향
  - (C) `CustomerPaymentService` 를 라이더 컨트롤러에서 호출 — 코드 추가 최소 / 이름과 실제가 어긋남
- **고른 것**: (A)
- **근거**: 고객·라이더 세션 인터셉터를 `common/auth` 로 공용 추출하지 않기로 한 판단과 같은 이유다
  (`CLAUDE.md` 「확인이 필요한 항목」) — 아직 존재하지 않는 차이를 미리 상정해 설계하지 않는다.
  게다가 라이더 쪽에는 곧 정산 내역·거래 내역·출금(#68~)이 붙고 그것들은 고객과 전혀 겹치지 않으므로,
  지금 공용 클래스를 만들면 곧 다시 갈라야 한다.
- **영향**: #68 이후 라이더 포인트 기능은 전부 `RiderPaymentService` 에 붙인다. 두 서비스가 실제로
  같은 규칙을 세 번째로 반복하게 되면 그때 추출을 다시 논의한다.

### 2. 지갑이 없을 때의 응답 코드

- **물었던 것**: 고객 구현은 `IllegalStateException` → `GlobalExceptionHandler` → **400** 인데,
  `CustomerPointApi` 문서에는 **500** 이라고 적혀 있어 계약과 실제가 어긋나 있다. 라이더는 어느 쪽으로 갈지
- **선택지**:
  - (A) 라이더만 500 — 문서와 일치 / 두 API 의 동작이 달라짐
  - (B) 고객과 동일하게 400 유지 — 동작 일치 / 문서와 어긋난 상태를 라이더 쪽에도 복제
  - (C) 라이더 500 + 고객 문서·구현도 함께 정정 — 불일치 해소 / 이슈 범위 밖이고 고객 API 응답 계약이 바뀌어 프론트 영향 검토 필요
- **고른 것**: (A)
- **근거**: 지갑 없음은 클라이언트가 고칠 수 없는 서버 데이터 정합성 오류라 4xx 가 아니다.
  고객 쪽 400 은 이번 이슈 범위 밖이라 건드리지 않고 미결로 남긴다.
- **영향**: `BusinessException(INTERNAL_SERVER_ERROR)` 를 쓴다. 두 API 의 같은 상황이 서로 다른
  상태코드를 내는 기간이 생기므로 아래 「새로 생긴 미결 사항」에 올렸다.

### 3. 테스트 실행 주체

- **물었던 것**: 작업 환경(샌드박스)에 Java 21·Docker·MySQL·Redis 가 없어 `./gradlew test` 를 돌릴 수 없음
- **고른 것**: 테스트는 작성하고 실행은 사람이 한다
- **영향**: 아래 「검증하지 못한 것」에 명시. 실행하지 않은 테스트를 통과했다고 적지 않는다.

## 스스로 판단한 것

- **진행 중 출금을 잔액에서 다시 빼지 않는다** — 근거: 이슈 비고("진행 중이거나 실패한 출금 요청을 중복
  반영하지 않는다")를 "보정을 더한다"가 아니라 "보정하지 않는다"로 읽었다. 출금은 요청 시점에 잔액을
  **선차감**하고 WITHDRAWAL 원장을 남기며 실패 시 WITHDRAWAL_REFUND 로 되돌리는 설계(`RiderPointApi`)라,
  PENDING 은 이미 빠져 있고 실패는 이미 복구돼 있다. 여기서 또 빼면 그게 이중 반영이다. 같은 이유로
  잔액을 `point_transaction` 합산으로 다시 계산하지 않고 `point_wallet.balance` 를 정본으로 그대로 읽는다.
- **인터셉터에 `/api/rider/points` 와 `/api/rider/points/**` 를 함께 등록** — 근거: 이번 이슈는 잔액 하나뿐이지만
  같은 접두어 아래 정산·거래 내역·출금이 예정돼 있다. 등록 누락은 "API 가 인증 없이 열린 채로 배포되는"
  실패이므로, 아직 없는 경로를 미리 닫아 두는 쪽이 나중에 여는 것보다 안전하다.
- **통합 테스트를 따로 두지 않았다** — 근거: 이 기능에는 트랜잭션 경계도 DB 제약도 동시성도 없다.
  JPA 매핑·Flyway 정합성은 E2E 가 실제 컨텍스트를 띄우면서 같이 검증한다. 세 층에서 같은 것을 세 번
  확인하면 시간만 쓴다(`references/testing.md`).
- **회원가입 API 대신 리포지토리로 픽스처를 만들었다** — 근거: 라이더 회원가입은 휴대전화 인증 토큰이
  필요해 이 이슈와 무관한 사전 조건이 늘어난다. 대신 `RiderSignupService` 가 한 트랜잭션에서 만드는
  세 가지(회원·프로필·지갑)를 그대로 넣었다.

## 일부러 하지 않은 것

- **고객 잔액 조회의 400 vs 문서 500 불일치 정정**: 이유 — 이슈 범위 밖이고 기존 API 의 응답 계약이
  바뀌면 프론트 영향 검토가 필요하다 — 후속: 미등록(아래 미결 사항)
- **화면 연동**: 이유 — 이슈에 화면이 없다. 없는 화면을 상상해 만들지 않는다 — 후속: 라이더 포인트 화면 이슈
- **고객 세션 쿠키로 호출하면 401** E2E 케이스: 이유 — 역할 검증은 `RiderSessionInterceptor` 의 책임이고
  이미 그쪽 테스트가 덮는다. 잔액 조회에서 다시 확인할 것이 없다
- **출금 선차감이 잔액에 반영되는지에 대한 통합 테스트**: 이유 — 출금 기능(#68~)이 아직 없어 재현할 수 없다
  — 후속: 출금 구현 이슈에서 함께 덮는다

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `payment/service/RiderPaymentServiceTest` | 지갑 잔액을 그대로 반환 / 신규 라이더는 0(지갑 없음과 구분) / 지갑 없음은 500 |
| 통합 | 해당 없음 | 위 「스스로 판단한 것」 참조 |
| E2E | `payment/controller/RiderPointBalanceE2ETest` | 로그인 후 조회 200 + 잔액 / 잔액 0 / 쿠키 없이 401(인터셉터 등록 누락 회귀) / 지갑 없음 500 |

실행 결과:

```text
./gradlew test → 실행하지 않음
```

### 검증하지 못한 것

- **테스트를 한 번도 실행하지 않았다.** 작업 환경에 Java 21·Docker·MySQL 8.4·Redis 가 없다.
  아래를 사람이 돌려 확인해야 한다.

  ```bash
  cd backend
  docker compose up -d && docker compose ps       # healthy 확인
  ./gradlew test --tests '*RiderPaymentServiceTest' --tests '*RiderPointBalanceE2ETest'
  ```

- 프론트는 손대지 않았다. `RiderPaymentController` 는 이번 작업 전에도 이미 `@RestController` 로
  등록돼 있어 `getRiderPointBalance` 가 `/v3/api-docs` 에 나와 있었다(응답 본문만 `null` 이었다).
  즉 OpenAPI 스펙이 바뀌지 않아 `pnpm generate:api` 재생성이 필요 없고, `OpenApiOperationIdE2ETest`
  결과도 이전과 같아야 한다.

## 새로 생긴 미결 사항

- 포인트 지갑 없음의 응답 코드가 고객(400)과 라이더(500)로 갈린다(#67). 라이더는
  `BusinessException(INTERNAL_SERVER_ERROR)` 로 `RiderPointApi` 문서와 맞췄지만, 고객
  (`CustomerPaymentService`)은 `IllegalStateException` → `GlobalExceptionHandler` → 400 이라
  `CustomerPointApi` 문서에 적힌 500 과 어긋난 상태 그대로다. 고객 쪽을 500 으로 맞추려면 기존 API 의
  응답 계약이 바뀌므로 프론트 영향 검토가 필요하다 — 별도 이슈로 올릴지 미결
- `GlobalExceptionHandler` 가 `IllegalStateException` 을 일괄 400 으로 바꾸고 있어, 서버 정합성 오류를
  400 과 구분하려면 매번 `BusinessException` 을 써야 한다. 이 관례를 명시할지 미결
