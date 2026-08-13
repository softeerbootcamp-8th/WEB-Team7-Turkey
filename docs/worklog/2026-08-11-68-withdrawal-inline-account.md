# 라이더 출금 신청 시 계좌정보 동봉 방식으로 계약 변경 작업 기록

- 이슈: [#68](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/68) (CLOSED, 계약 재변경) / 관련: #87(계좌 등록 API, 미구현)
- 브랜치: `cdci/mobile-apk-artifact` (세션 지정 브랜치 그대로 사용, 별도 feature 브랜치 미생성)
- 범위: backend
- 작성일: 2026-08-11

## 무엇을 만들었나

`POST /api/rider/points/withdrawals`의 요청 바디에 `bankCode`·`accountNumber`·`accountHolderName`을 추가했다.
기존에는 `RiderPayoutAccount`(라이더당 1행, 암호화 저장)에 사전 등록된 계좌를 조회해 스냅샷을 복사하는 방식이었으나,
`RiderPayoutAccount`를 등록하는 컨트롤러(#87)가 아직 없어 이 경로가 항상 409로 막혀 있었다. 신청 시점에
계좌 정보를 입력받아 즉시 마스킹 후 `rider_withdrawal`에 스냅샷만 남기는 방식으로 바꿨다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| POST | `/api/rider/points/withdrawals` | 출금 신청(계좌정보 포함) | 400 최소금액 미달/계좌형식 오류, 409 잔액부족·동시재전송 |

### 화면

해당 없음(이 작업은 백엔드 계약 변경만 다룬다. #219 프론트 연동은 별도).

### 스키마 변경

해당 없음. `rider_withdrawal` 테이블(V11)이 이미 `bank_code_snapshot`·`masked_account_number_snapshot`·
`account_holder_name_snapshot` 컬럼을 갖고 있어 마이그레이션이 필요 없었다.

## 사람이 고른 선택

### 1. 출금 계좌 처리 방식

- **물었던 것**: 기존 `RiderPayoutAccount` 사전 등록 모델을 완성(#87 구현)할지, 신청 시 계좌정보 동봉으로
  계약 자체를 바꿀지.
- **선택지**:
  - (A) #87 등록 API 구현 — 장점: 기존 암호화 설계·재사용 계좌 유지, 라이더가 매번 입력 안 해도 됨.
    단점: 등록 화면·API를 새로 만들어야 함.
  - (B) 신청 시 계좌정보 동봉 — 장점: 등록 화면 없이 즉시 출금 흐름 완성. 단점: 매번 입력 필요,
    출금마다 다른 계좌로 보낼 실수 위험, `RiderPayoutAccount`(암호화 인프라 포함)가 참조를 잃음.
- **고른 것**: (B)
- **근거**: 모의 출금 MVP 범위에서는 등록 화면 없이 흐름을 완성하는 쪽이 더 간단하다는 판단.
- **영향**: `RiderWithdrawal.request()` 시그니처가 `RiderPayoutAccount` 대신 `RiderProfile`+계좌 필드
  개별 인자를 받도록 바뀜. `WithdrawalRequest` DTO에 계좌 필드 3개 추가.

### 2. 기존 `RiderPayoutAccount` 코드(엔티티·리포지토리·암호화 인프라) 처리

- **물었던 것**: 더 이상 참조하지 않게 된 `RiderPayoutAccount`·`RiderPayoutAccountRepository`를
  삭제할지, 코드로 남겨둘지.
- **선택지**:
  - (A) 완전 삭제 — 장점: 죽은 코드 제거. 단점: 나중에 사전 등록 방식으로 돌아가면 재작성 필요.
  - (B) 코드는 두고 참조만 제거 — 장점: `order:geo`처럼 의도된 데드코드로 보존, 재사용 가능성 열어둠.
    단점: 당분간 아무도 안 쓰는 클래스가 남음.
- **고른 것**: (B)
- **근거**: 나중에 계좌 사전등록 방식으로 되돌릴 가능성을 열어두기 위해.
- **영향**: `RiderPayoutAccount.java`·`RiderPayoutAccountRepository.java`·`RiderPayoutAccountTest.java`는
  코드베이스에 남아 있지만 어떤 컨트롤러·서비스도 참조하지 않는다(V7 마이그레이션의 `rider_payout_account`
  테이블도 같은 이유로 존재하되 비어 있음 — CLAUDE.md의 `order:geo` 선례와 동일 패턴).

## 스스로 판단한 것

- **계좌번호 마스킹 방식**: 뒤 4자리만 남기고 나머지를 `*`로 채우는 방식(`RiderPaymentService.maskAccountNumber`)을
  새로 만들었다 — 기존 `RiderPayoutAccount`는 마스킹을 서비스 계층 밖(엔티티 생성 이전)에서 받는 구조였고
  재사용할 기존 유틸리티가 없었다. 원본 계좌번호는 이 메서드 호출 지점 이후 어디에도 보관하지 않는다.
- **계좌번호 검증 규칙**: `@Pattern(regexp = "\\d{6,20}")`로 숫자 6~20자리만 허용. 은행별 정확한 자릿수
  규칙까지는 이 변경 범위에서 다루지 않았다(모의 출금이라 실제 계좌 검증 자체가 없음).
- **`RiderProfile` 조회 방식**: 잠금이 필요 없는 참조라 `riderProfileRepository.getReferenceById()`로
  프록시만 얻었다(`DeliveryService`·`CustomerPaymentService`의 기존 패턴과 동일).

## 일부러 하지 않은 것

- **#87(계좌 등록·변경 API) 구현**: 이번 계약 변경으로 필요성이 사라졌다. #219(프론트 연동)에서
  "출금 계좌 등록·변경" 항목을 참조하고 있어 이슈 본문 정합성 확인이 필요하다 — 후속.
- **`docs/03-erd.md`·#219 이슈 본문의 `rider_payout_account` 참조 정리**: 문서 정합성 작업은 이번
  범위에 포함하지 않았다.
- **E2E 테스트**: 이 저장소에 이미 이 엔드포인트의 E2E 테스트가 없었고(#68은 이미 CLOSED된 이슈의
  재작업), 사용자 지시(테스트 코드 작성 안 함)에 따라 새 테스트를 추가하지 않았다. 기존 단위 테스트
  (`RiderWithdrawalTest`, `RiderPaymentServiceTest`)는 시그니처 변경으로 컴파일이 깨져 최소 수정만 했다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderWithdrawalTest.java` | 새 시그니처로 PENDING 생성, 스냅샷 저장, 필수값 가드(라이더·계좌정보) |
| 단위 | `RiderPaymentServiceTest.java` | 생성자 의존성 변경 후 잔액 조회 로직 회귀 없음 |
| 통합 | 해당 없음 | 스키마 변경 없어 생략 |
| E2E | 해당 없음 | 기존에도 없었고 이번 범위에서 추가하지 않음(위 「일부러 하지 않은 것」) |

실행 결과:

```text
./gradlew compileJava compileTestJava → BUILD SUCCESSFUL
./gradlew test --tests '*RiderWithdrawalTest' --tests '*RiderPaymentServiceTest' --tests '*RiderPayoutAccountTest'
  → RiderPayoutAccountTest: tests=2, failures=0
  → RiderPaymentServiceTest: tests=3, failures=0
  → RiderWithdrawalTest: tests=11, failures=0
./gradlew test (전체) → background 실행, 완료(성공)
```

### 검증하지 못한 것

- 실제 HTTP 요청 기준 400/409 응답 본문(E2E 부재로 서비스·도메인 계층까지만 확인).

## 새로 생긴 미결 사항

- **#219 이슈 본문의 "출금 계좌 등록·변경(#87)" 의존성이 이번 계약 변경으로 무효화됐다.** 프론트 연동
  작업 시 계좌 등록 화면 대신 출금 신청 폼에 계좌 입력 필드를 포함하도록 이슈 범위를 다시 확인해야 한다.
- `rider_payout_account` 테이블·엔티티는 아무 코드도 안 쓴다(V7). `rider_location_history`(V15)와 같은
  종류의 의도된 데드 상태다 — CLAUDE.md 「확인이 필요한 항목」에 반영 필요.
