# 라이더 로그아웃 작업 기록

- 이슈: [#51](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/51) [RIDE-LOGIN-003] 라이더 로그아웃
- 브랜치: `feature/51-rider-logout`
- 범위: backend
- 작성일: 2026-07-29

## 무엇을 만들었나

라이더 로그아웃 API를 추가했다. 고객 로그아웃(#28)이 세션 삭제·쿠키 만료만 하는 것과 달리,
이슈가 요구한 대로 **운행 상태를 함께 다룬다**: BUSY면 로그아웃을 거부하고, AVAILABLE이면
UNAVAILABLE로 바꿔 배차 후보에서 제외한 뒤, 서버 세션 삭제 + 쿠키 만료를 수행한다.
이미 만료·삭제된 세션에도 200으로 완료 처리한다(멱등). 세션 삭제·쿠키 만료는 #28과 동일하게
컨트롤러가 담당하고, 라이더에만 있는 운행 상태 전이(도메인 규칙)만 서비스 계층(`RiderLogoutService`)으로 뺐다.
컨트롤러는 명세(`RiderLogoutApi` 인터페이스에 `@Tag`·`@Operation`·매핑 애노테이션)와 구현(`RiderLogoutController`)을
분리하는 팀 관례(Discussion #245, dev 스킬 업데이트)를 따른다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| POST | `/api/rider/logout` | 라이더 로그아웃(세션 삭제 + 쿠키 만료 + 상태 정리) | 409 (BUSY 상태라 거부) |

### 화면

해당 없음. 이슈에 화면 언급이 없어 backend 범위로 판정했다. 프론트 Orval 재생성도 하지 않았다
(이 이슈에서 소비하는 화면이 없음).

### 스키마 변경

해당 없음. 기존 `rider_profile.operating_status`만 사용한다.

## 사람이 고른 선택

### 1. BUSY 상태 로그아웃 거부 시 HTTP 상태 코드

- **물었던 것**: 이슈에 거부 응답 코드가 미지정. 프론트 계약이라 확정 필요.
- **선택지**:
  - (A) 409 Conflict — 현재 운행 상태와 충돌해 거부하는 것이므로 상태 충돌 의미. CLAUDE.md의 "진행 중 1건 제한 위반 409" 선례와 결이 같음
  - (B) 400 Bad Request — 요청 형식 문제가 아니라 서버 상태 때문이라 의미가 덜 맞음
  - (C) 423 Locked — 정확하지만 이 저장소에서 쓰인 선례가 없어 낯섦
- **고른 것**: (A) 409 Conflict
- **근거**: 현재 상태와의 충돌이라는 의미가 가장 정확하고, 저장소에 이미 있는 409 관례(BusinessException(CONFLICT, ...))와 일관됨.
- **영향**: 프론트는 로그아웃 요청에 대해 409를 "배송 완료 후 재시도" 안내로 처리해야 한다. `BusinessException(HttpStatus.CONFLICT, ...)` → GlobalExceptionHandler가 그대로 반영.

### 2. "배차 후보에서 제외"의 구현 범위

- **물었던 것**: 이슈 ⑤에 "배차 후보에서 제외"가 있으나 현재 Redis GEO 후보 스토어가 미구현.
- **선택지**:
  - (A) 상태 전이(AVAILABLE→UNAVAILABLE)로만 처리 — 배차 후보 조회는 AVAILABLE만 대상으로 할 것이므로 상태 전이가 곧 제외. 새 인프라 없음
  - (B) 지금 Redis GEO 후보 스토어까지 구축 — 이슈 범위를 크게 넘고 새 인프라 도입(CLAUDE.md상 별도 확인 필요)
- **고른 것**: (A) 상태 전이로만 처리
- **근거**: `location/` 패키지가 아직 빈 스텁이라 실제로 제거할 GEO 멤버십이 존재하지 않는다. 배차 후보는 본질적으로 "AVAILABLE 라이더"이므로 UNAVAILABLE 전이가 곧 제외다. GEO 스토어를 여기서 만드는 것은 이슈 범위 밖의 인프라 신설.
- **영향**: 향후 배차 후보 GEO 스토어가 생기면, 로그아웃 시 상태 전이와 함께 GEO 멤버 제거도 한 트랜잭션/afterCommit로 묶어야 한다(그때 이 서비스에 한 단계 추가). 지금은 상태만으로 정합성이 성립.

## 스스로 판단한 것

- **인터셉터에 등록하지 않고 컨트롤러가 쿠키를 직접 읽음**: 이슈는 "이미 만료된 세션이면 로그아웃 완료(200)"를 요구하는데, `RiderSessionInterceptor`는 그 경우 401을 던진다. 인터셉터 뒤에 두면 멱등 요구와 충돌하므로 `RiderWebMvcConfig.addPathPatterns`에 `/api/rider/logout`을 **등록하지 않았다**(#28 고객 로그아웃과 같은 판단).
- **세션 삭제 순서: 컨트롤러가 상태 전이 커밋 뒤에 삭제** (리뷰 논의로 방향 변경). 상태 전이(MySQL)와 세션 삭제(Redis)는 원자적으로 묶을 수 없어, 세션을 먼저 지운 뒤 상태 커밋이 실패하면 "로그아웃됐지만 여전히 AVAILABLE(배차 후보)"인 라이더가 남는 정합성 구멍이 있다. 그래서 세션 삭제는 상태 전이가 확정된 뒤에만 일어나야 한다.
  - **처음 구현**: 서비스가 세션까지 다루며 `TransactionSynchronizationManager.afterCommit`으로 삭제를 지연했다. 동작은 맞지만, 세션 삭제·쿠키 만료를 컨트롤러가 하는 **고객 로그아웃(#28) 관례를 깼고**, 프로덕션에선 절대 안 타는(트랜잭션 밖 전용) 폴백 분기가 순수 단위 테스트 때문에 생겼다(test-induced 구조).
  - **최종**: 세션 조회·삭제·쿠키 만료를 #28처럼 **컨트롤러**로 옮기고, 서비스는 `changeStatusForLogout(memberId)`로 상태 전이만 한다. 순서 보장은 트랜잭션 경계로 자연 확보 — `@Transactional` 서비스가 정상 리턴하면 커밋이 끝난 상태라, 컨트롤러가 그 뒤에 세션을 지운다. BUSY면 서비스가 409를 던져 컨트롤러의 세션 삭제·쿠키 만료 지점에 도달하지 않으므로 세션이 유지된다. `TransactionSynchronizationManager`와 폴백 분기는 제거됐다.
- **BUSY 판정 후 goOffline() 미호출**: `RiderProfile.goOffline()`은 AVAILABLE에서만 허용되고 그 외에는 IllegalStateException을 던진다. 그래서 BUSY는 그 전에 409로 막고, UNAVAILABLE은 이미 오프라인이라 전이를 건너뛴다. goOffline()은 상태가 정확히 AVAILABLE일 때만 호출한다.
- **예외 타입**: 상태 전이 거부는 도메인 `IllegalStateException`(엔터티)이 아니라 서비스에서 `BusinessException(CONFLICT)`로 던진다 — 409를 정확히 내보내기 위해. (엔터티의 IllegalStateException는 이 경로에서는 도달하지 않는다.)

## 일부러 하지 않은 것

- **Redis GEO 배차 후보 스토어**: 미구현 상태 그대로 뒀다 — 이유는 위 「사람이 고른 선택 2」. 후속: GEO 후보 스토어 구현 이슈에서 로그아웃 경로에 GEO 제거를 연결해야 함 → 이슈 #240.
- **프론트 연동/Orval 재생성**: 이 이슈는 backend 범위. 로그아웃 버튼 화면 연결은 별도 프론트 이슈에서.
- **BUSY 롤백의 멀티스레드 재현 테스트**: 단일 요청 경로라 동시성 재현 테스트는 두지 않았다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `rider/service/RiderLogoutServiceTest.java` | 상태 전이만(세션은 컨트롤러 책임): AVAILABLE→UNAVAILABLE / UNAVAILABLE 유지 / BUSY 409 거부·상태 유지 |
| 통합 | `rider/service/RiderLogoutServiceIntegrationTest.java` | 실제 MySQL에서 AVAILABLE→UNAVAILABLE 커밋 / BUSY 409 롤백으로 DB 불변 / UNAVAILABLE 유지 |
| E2E | `rider/controller/RiderLogoutE2ETest.java` | HTTP 전 경로: AVAILABLE 로그아웃 200·Max-Age=0·상태 UNAVAILABLE·이후 세션확인 401 / BUSY 409·세션 유지(세션확인 200) / UNAVAILABLE 200 / 쿠키 없음 200 / 미상 세션 200 |

세션 삭제·쿠키 만료의 검증은 E2E(엔드포인트 전 경로)로 모은다 — 리팩터로 그 책임이 컨트롤러로 옮겨졌기 때문. 단위·통합은 서비스의 상태 전이만 본다.

실행 결과:

```text
./gradlew test → BUILD SUCCESSFUL (전체 통과)
  신규: 단위 3, 통합 3, E2E 5 = 11개 모두 통과
  OpenApiOperationIdE2ETest 4개 통과 (riderLogout operationId 유일성 확인)
```

### 검증하지 못한 것

- 인메모리 `InMemorySessionStore`를 써서 **실제 Redis 세션 TTL 만료 동작은 검증하지 못했다**(기존 E2E와 동일한 한계, CLAUDE.md 「확인이 필요한 항목」에 이미 등재).
- `afterCommit` 세션 삭제가 **커밋 실패 시 실행되지 않는다는 것**(afterCommit이 아니라 afterCompletion을 쓰지 않았다는 것)은 통합 테스트에서 정상 커밋 경로로만 확인했고, 커밋 강제 실패 케이스는 두지 않았다.

## 새로 생긴 미결 사항

- 배차 후보 GEO 스토어가 구현되면 로그아웃 시 상태 전이와 GEO 멤버 제거를 함께 처리해야 한다(현재는 상태 전이만으로 제외). → CLAUDE.md 「확인이 필요한 항목」에 한 줄 추가.
