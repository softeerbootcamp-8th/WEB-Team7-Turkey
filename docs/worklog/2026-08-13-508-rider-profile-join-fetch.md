# 라이더 프로필 조회 쿼리 간소화 작업 기록

- 이슈: [#508](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/508)
- 브랜치: `feature/508-rider-profile-join-fetch`
- 범위: domain (인터셉터·리포지토리 내부 최적화, API 계약 변경 없음)
- 작성일: 2026-08-13

## 무엇을 만들었나

`RiderSessionInterceptor`가 요청당 `memberRepository.findById()` + `riderProfileRepository.findById()`
두 번의 별도 트랜잭션(=커넥션 acquire 2회)을 열던 것을, `RiderProfileRepository.findWithMemberById()`
(join fetch) 한 번으로 합쳤다. `RiderProfile.member`는 PK 공유 `@OneToOne(LAZY)`라 SQL 자체를
JOIN 하나로 대체할 수 있었다. 라이더 API 18개 전부(위치 갱신은 3회 → 2회)에 적용된다.

### API

해당 없음 — 응답 스키마·상태코드 변화 없음(이슈 요구사항: "동작 변화 없음").

### 화면

해당 없음.

### 스키마 변경

해당 없음 — 기존 컬럼·제약으로 충분(PK 공유 관계라 JOIN에 새 인덱스 불필요).

## 사람이 고른 선택

이슈 본문에 구현 코드(쿼리, 인터셉터 로직)가 그대로 명시돼 있어 계약이 갈리는 지점이 없었다.
단계 2 게이트에서 사람에게 따로 물은 것 없음 — 이슈가 이미 "무엇을, 왜, 어떻게"를 확정해서 왔다.

## 스스로 판단한 것

- **인터셉터의 판정 순서 변화**: 기존은 `member 조회 → role/active 체크 → profile 조회` 순이라,
  고객 세션으로 라이더 API를 치면 "member는 찾았지만 role이 CUSTOMER"라서 401이 났다. 새 코드는
  `profile 조회(join fetch) → role/active 체크` 순이라, 고객은 애초에 `rider_profile` 행이 없으므로
  프로필 조회 단계에서 이미 401이 난다(role 체크는 도달하지 않음). 최종 응답(401)은 동일하지만
  거쳐가는 분기가 바뀐다는 것을 인지하고 진행 — 라이더는 가입 시 `RiderProfile`이 항상 함께
  생성되므로(#50) 실제 운영 데이터에서 "RIDER role인데 profile 없음"은 일어나지 않는다. role 체크는
  방어적으로 이슈 원안 그대로 유지했다.
- **`memberRepository` 필드 제거**: 인터셉터가 더 이상 `MemberRepository`를 쓰지 않게 되어(회원 정보는
  `profile.getMember()`로 얻음) 생성자 인자와 `RiderWebMvcConfig`의 배선에서도 함께 제거했다. 안 쓰는
  의존성을 남겨두면 다음 사람이 "왜 안 쓰는데 주입돼 있지"를 다시 캐야 한다.
- **기존 `findById`/`findByIdForUpdate`는 그대로 둠**: 이슈에서 명시한 대로 인터셉터 전용 경로에만
  `findWithMemberById`를 추가했다. 배차 관련 락 쿼리(`findByIdForUpdate`)는 이 변경과 무관.

## 일부러 하지 않은 것

- **maximum-pool-size 조정, DB 인스턴스 재사이징**: 이슈가 명시적으로 "완화책이지 근본 해법 아님"이라
  선을 그었고, MySQL CPU가 먼저 포화된다는 이전 실측 근거로 범위 밖이라 판단. 후속: 이미
  `CLAUDE.md`「알려진 결함」/「인프라」 항목에 유사 메모 존재, 새 항목 추가하지 않음(중복).
- **배차 수락/주문 생성의 `REQUIRES_NEW` 분리 되돌리기**: 이슈가 명시적으로 손대지 말라고 한 부분(#446
  데드락 회피 근거) — 건드리지 않음.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderSessionInterceptorTest` | 정상 인증, 고객 세션 401, 프로필 없음 401, 세션 TTL 연장(BUSY), 인증 실패 시 미연장, 만료 쿠키 응답 — 전부 `findWithMemberById` 단일 mock으로 갱신 |
| 통합/E2E | 기존 rider 패키지 전체 + location 패키지 전체(회귀 확인) | 인터셉터를 거치는 모든 라이더 API 401/200 경로가 새 쿼리 경로에서도 동일하게 통과하는지 |

실행 결과:

```text
./gradlew test --tests 'com.turkey.quick.rider.auth.RiderSessionInterceptorTest' → 6 tests, 0 failures
./gradlew test --tests 'com.turkey.quick.rider.*' → 196 tests, 0 failures, 0 errors
./gradlew test --tests 'com.turkey.quick.location.*' → 107 tests, 0 failures, 0 errors
```

### 검증하지 못한 것

- 실제 HikariCP acquire 횟수 감소(2→1)를 부하테스트로 재계측하지는 않았다 — 이슈의 근거 자료(부하테스트
  전수조사)가 이미 있고, 이번 변경은 그 조사가 지목한 코드를 그대로 고친 것이라 별도 재계측 없이 진행.
  필요하면 `loadtest` 스킬로 회귀 확인 가능.

## 새로 생긴 미결 사항

- 없음.
