# 회원가입 시 포인트 지갑 생성 누락 수정 작업 기록

- 이슈: [#237](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/237)
- 브랜치: `feature/237-signup-point-wallet`
- 범위: backend (엔드포인트 추가 없음, 기존 회원가입 트랜잭션 안에 로직만 보강)
- 작성일: 2026-07-29

## 무엇을 만들었나

이슈는 고객 회원가입(#25)에서 `PointWallet` 생성이 빠졌다고 보고했다. 조사해보니 `PointWallet`
엔터티(`payment/domain/PointWallet.java`)와 `point_wallet` 테이블(`V5__create_point_wallet.sql`)은
이미 있었지만, 이를 저장하는 `PointWalletRepository`가 아예 없었고 `CustomerSignupService`도
`RiderSignupService`(#48)도 지갑을 만들지 않았다. 두 서비스 모두 같은 트랜잭션 안에서 `Member`
저장 직후 `PointWalletRepository.save(PointWallet.create(member))`를 호출하도록 고쳤다.

### API

없음. 기존 `POST /api/customer/signup`, `POST /api/rider/signup`의 내부 처리만 변경.

### 화면

해당 없음.

### 스키마 변경

해당 없음 (`point_wallet` 테이블은 이미 존재).

## 사람이 고른 선택

### 1. 라이더 회원가입도 같이 고칠지

- **물었던 것**: 이슈 #237은 고객 회원가입만 언급하는데, 조사 중 라이더 회원가입(#48)에도 동일한
  누락이 있는 것을 발견했다. 이번 작업에서 같이 고칠지, 아니면 이슈 범위를 좁게 지켜 별도 이슈로
  등록할지.
- **선택지**:
  - (A) 같이 고친다 — 동일한 버그를 하나의 이슈로 묶어 처리. 장점: 나중에 라이더만 다시 발견되는
    것을 방지. 단점: 이슈 문구보다 범위가 넓어짐.
  - (B) 고객만 고친다 — 이슈 문구에 정확히 맞춤. 장점: 범위가 명확. 단점: 라이더 쪽 버그가 남아
    있다가 나중에 또 별도로 발견·보고됨.
- **고른 것**: (A)
- **근거**: 동일한 버그이므로 하나의 이슈(#237)로 묶어 고객·라이더 둘 다 수정.
- **영향**: `RiderSignupService`, `RiderSignupServiceTest`, `RiderSignupE2ETest`도 이번 커밋에
  포함됨. PR 설명에 라이더 쪽도 같이 고쳤다는 점을 명시해야 리뷰어가 이슈 범위 확장을 인지할 수 있다.

## 스스로 판단한 것

- **`PointWalletRepository`는 `save`/`findById`만 제공하는 빈 `JpaRepository`로 만듦**: 이번
  이슈는 "가입 시 지갑 생성"만 다루고, 잔액 충전·차감·조회 API는 아직 별도 이슈로 남아 있다
  (`docs/03-erd.md`, `point_charge`/`point_transaction` 테이블은 있지만 서비스 계층 미구현).
  지금 필요하지 않은 커스텀 쿼리 메서드를 미리 추가하지 않았다.
- **지갑 생성 위치는 `Member` 저장 직후, 약관 동의 저장 이전**: `RiderProfile` 생성과 동일한
  자리(라이더는 이미 프로필을 이 위치에서 만들고 있었음)에 맞춰 순서를 통일했다. 트랜잭션이 하나이므로
  순서 자체가 원자성에 영향을 주진 않지만, 실패 시 로그/스택트레이스를 볼 때 일관된 순서가 읽기 쉽다.

## 일부러 하지 않은 것

- **포인트 잔액 조회/충전 API**: 이슈 범위 밖(#237은 "가입 시 지갑 생성 누락"만 다룸). 관련 미결
  사항은 이미 `CLAUDE.md`「확인이 필요한 항목」에 "포인트 차감·환불 시점, 포인트 동시성 처리 방식"으로
  등록되어 있어 추가로 적지 않음.
- **기존 가입자에 대한 지갑 백필(backfill) 마이그레이션**: 이슈에 언급 없음. 로컬/dev 환경에 이미
  가입된 회원이 있다면 지갑이 없는 상태로 남는다. 운영 반영 전 필요하면 별도 확인 필요 — 아래
  「새로 생긴 미결 사항」에 남김.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `CustomerSignupServiceTest`, `RiderSignupServiceTest` | 정상 가입 시 잔액 0인 지갑 생성 / 아이디·휴대전화 중복, DB 유니크 위반, 약관 미동의로 가입 실패 시 지갑을 만들지 않음(No wallet on failure) |
| 통합/E2E | `CustomerSignupE2ETest`, `RiderSignupE2ETest` | 실제 HTTP 요청으로 가입 후 `PointWalletRepository`에서 `member_id`로 지갑 조회, 잔액 0 확인 |

실행 결과:

```text
cd backend && ./gradlew test --tests '*CustomerSignupServiceTest' --tests '*RiderSignupServiceTest' --tests '*PointWalletTest'
→ BUILD SUCCESSFUL

cd backend && ./gradlew test --tests '*CustomerSignupE2ETest' --tests '*RiderSignupE2ETest'
→ BUILD SUCCESSFUL (로컬 MySQL 대상)

cd backend && ./gradlew test
→ BUILD SUCCESSFUL (전체 스위트, 회귀 없음)
```

### 검증하지 못한 것

- 회원가입 동시 요청(같은 아이디로 동시 가입) 상황에서 지갑 생성까지 포함한 멀티스레드 경쟁 테스트는
  하지 않음 — 기존에도 회원가입 자체의 동시 경쟁 통합 테스트가 없다는 미결 사항이 `CLAUDE.md`에
  이미 등록되어 있고(#25), 이번 변경은 그 트랜잭션 안에 저장 하나를 추가한 것이라 별도 경쟁 시나리오를
  새로 만들지 않았다.

## 새로 생긴 미결 사항

- 없음. (이번 수정 이전에 가입된 기존 회원 데이터 자체가 없어 백필 문제가 발생하지 않음 — 사람 확인,
  2026-07-29)
