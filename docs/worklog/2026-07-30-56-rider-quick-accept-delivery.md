# [RIDE-QUICK-004] 배달 확정하기 작업 기록

- 이슈: [#56](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/56)
- 브랜치: `feature/56-rider-quick-accept-delivery` (base: `feature/57-rider-quick-request-detail`, #55/#57 미머지 상태에서 이어 작업)
- 범위: backend
- 작성일: 2026-07-30

## 무엇을 만들었나

라이더가 WAITING 배송요청을 수락해 배차를 확정하는 API를 구현했다. 이 저장소에서 가장 정합성이
중요한 지점(동시성 경쟁)이라, GitHub Wiki의 [ADR-006 배차 동시성 처리](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/wiki/ADR%E2%80%90006-%EB%B0%B0%EC%B0%A8-%EB%8F%99%EC%8B%9C%EC%84%B1-%EC%B2%98%EB%A6%AC)에서
확정한 조건부 UPDATE(Compare-And-Set) 방식을 그대로 구현했다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| POST | `/api/rider/requests/{deliveryId}/accept` | WAITING 주문을 ASSIGNED로, 라이더를 BUSY로 원자적으로 전환 | 401 미인증, 403 라이더 상태가 AVAILABLE 아님, 404 존재하지 않는 주문, 409 취소/이미 배차/라이더가 다른 배송 수행 중 |

### 화면

해당 없음 — #55/#57과 같은 이유로 backend 범위로 판정.

### 스키마 변경

해당 없음 — `delivery_order`/`rider_profile`의 기존 컬럼과 제약(`uk_delivery_active_rider` 등,
ADR-006이 전제하는 구조)을 그대로 사용한다.

## 사람이 고른 선택

### 1. 실패 시 에러 코드 필드 신설 여부

- **물었던 것**: ADR-006은 "조건부 UPDATE는 0행만 돌려주고 이유는 알려주지 않으니, 실패 시 현재
  상태를 재조회해 사유를 구분한다"고 요구한다. 이 "구분된 사유"를 `ApiResponse`에 별도 에러코드
  필드로 추가할지, 기존처럼 `message` 문자열로만 구분할지.
- **선택지**:
  - (A) 기존 `message` 문자열로 사유 구분 — 기존 `ApiResponse`/`BusinessException` 구조를 안 건드림 /
    프론트가 문자열 매칭으로 분기해야 하면 취약함
  - (B) `ApiResponse`에 에러코드 enum 필드 신설 — 프론트가 안정적으로 분기 가능 / 공용 응답 구조를
    바꾸는 일이라 다른 모든 API에 영향, 이 이슈 범위를 크게 넘음
- **고른 것**: (A)
- **근거**: 공용 응답 구조 변경은 이 이슈 하나가 결정할 사안이 아니라고 판단.
- **영향**: 프론트에서 실패 사유별로 다른 UX(예: "취소된 요청입니다" 안내 vs 그냥 재시도 유도)를
  보여주려면 현재는 `message` 문자열을 파싱해야 한다. 필요해지면 별도 이슈로 에러코드 체계를 논의.

## 스스로 판단한 것 (ADR-006을 그대로 코드로 옮긴 것 포함)

- **CLAUDE.md의 미결 상태 정정**: 처음에 이 이슈를 시작할 때 CLAUDE.md 「확인이 필요한 항목」의
  "배차 동시성 제어 방식" 미결 표시만 보고 DDL 주석으로 대략 설계했다가, 실제로는 GitHub Wiki
  ADR-006에서 이미 상세히 확정돼 있다는 걸 사람이 지적해서 알게 됐다. CLAUDE.md 목록이 갱신되지
  않았던 것 — 이번에 ADR 링크를 달아 정정했다. **교훈**: 동시성/정책류 이슈는 DDL·엔터티 주석만
  보지 말고 Wiki ADR을 먼저 확인해야 한다.
- **잠금 순서 고정(주문 → 라이더)**: ADR-006이 데드락 회피를 위해 못박은 팀 컨벤션을 그대로
  따랐다. `assignIfWaiting` 호출 후에만 `markBusyIfAvailable`을 호출한다.
- **조건부 UPDATE를 네이티브 쿼리로 구현**: JPQL은 `SET`절에서 연관관계(`assignedRider`)를 직접
  대입할 수 없어(`o.assignedRider = :rider`는 되지만 FK 컬럼만 갱신하려면 참조 엔터티가 필요함),
  컬럼명을 직접 다루는 네이티브 쿼리(`nativeQuery = true`)가 더 단순하다고 판단했다. `assigned_rider_id`
  같은 컬럼명을 하드코딩하므로, 컬럼명이 바뀌면 컴파일 타임에 안 잡히고 테스트로만 잡힌다는 트레이드오프가 있다.
- **`@Modifying(clearAutomatically = true)`**: 조건부 UPDATE 이후 같은 트랜잭션에서 `findById`로
  최신 상태를 다시 읽으므로, 영속성 컨텍스트에 남아있을 수 있는 캐시를 비워 항상 DB의 실제 값을
  읽도록 했다.
- **`uk_delivery_active_rider` 위반을 `DataIntegrityViolationException`으로 캐치**: ADR-006이
  "한 라이더 두 주문"을 막는 두 번째 방어선(테이블 유니크 제약)으로 명시한 부분이다. 같은 라이더가
  서로 다른 두 주문을 동시에 수락하면 두 조건부 UPDATE 자체는 (타이밍에 따라) 둘 다 성공할 수
  있지만, `active_rider_id` UNIQUE가 둘째 커밋에서 막는다 — 이 예외를 서비스에서 잡아 409로
  변환하지 않으면 500으로 새어나간다. 통합 테스트(동시성 테스트)로 이 경로가 실제로 타는지 확인했다.
- **실패 사유 재조회 순서**: 주문 UPDATE가 0행이면 주문을 다시 조회해 CANCELED/그 외(이미 배차됨)를
  구분하고, 존재 자체가 없으면 404. 라이더 UPDATE가 0행이면 재조회 없이 바로 "다른 배송 수행 중"으로
  판정한다 — 라이더 자신의 상태 실패는 재조회할 필요 없이 원인이 명확하기 때문이다(ADR-006 표에서도
  `b=0`은 재조회 없이 고정 사유로 다룬다).

## 일부러 하지 않은 것

- **배차 포기(수락 후 취소)**: `RiderDeliveryRequestApi`의 기존 계약 주석에 이미 "MVP 범위 밖"으로
  명시돼 있어 손대지 않았다.
- **`skipDeliveryRequest`**: 여전히 스텁(`return null`)으로 남겨둠 — 별도 이슈 범위.
- **애플리케이션 레벨 락(1차 필터)**: ADR-006이 "지금 도입하지 않는다"고 명시했고, 인기 콜 폭주로
  인한 DB 부하가 실측되지 않은 이상 이번 이슈에서 도입하지 않았다.
- **`active_customer_id` 관련 처리**: ADR-006이 "배차가 아니라 배송요청 생성 제한이라 범위 밖"이라고
  명시한 그대로 따랐다. 기술적으로도 `acceptDeliveryRequest`는 `customer_id`를 전혀 읽거나 쓰지
  않으므로(건드리는 건 `status`/`assigned_rider_id`/`assigned_at`과 `rider_profile.operating_status`
  뿐) 이 트랜잭션에서 그 제약이 걸릴 방법 자체가 없다. 고객이 진행 중 배송요청을 이미 갖고 있는데
  또 만들려는 경우를 막는 건 주문 생성(REQ-ORD-002)의 책임이다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderDeliveryRequestServiceTest`(`AcceptDeliveryRequestTest` 중첩 클래스 추가) | AVAILABLE 아님 403, 존재하지 않는 주문 404, 취소된 주문 409, 이미 배차된 주문 409, `uk_delivery_active_rider` 위반 409 변환, 라이더 UPDATE 0행 시 409(부분 성공 없음), 정상 성공 응답 |
| 통합 | `RiderDeliveryRequestServiceIntegrationTest`(5개 케이스 추가) | 실제 MySQL로 성공 시 DB 상태(ASSIGNED+BUSY) 확인, 취소된 주문 409(상태 불변 확인), **두 라이더가 같은 주문을 동시에 수락하면 정확히 1명만 성공**, **한 라이더가 서로 다른 두 주문을 동시에 수락하면 정확히 1건만 배차** (둘 다 `CountDownLatch` 기반 멀티스레드 테스트, testing.md 예시 패턴) |
| E2E | `RiderDeliveryRequestE2ETest`(5개 케이스 추가) | 200(ASSIGNED/BUSY 응답), 401, 403, 404, 이미 배차된 주문 재수락 409 |

실행 결과:

```text
./gradlew test → BUILD SUCCESSFUL, 245 tests, 0 failures, 0 errors
  (#55/#57/#56 누적 45개: 단위 21 + 통합 13 + E2E 13. 이번 이슈 신규 17개, 회귀 없음)
pnpm typecheck → 실행하지 않음(백엔드 전용 이슈, 프론트 파일 변경 없음)
```

### 검증하지 못한 것

- #55/#57과 동일한 한계: 브라우저 E2E 없음.
- 동시성 테스트는 스레드 2개(경쟁 참가자 2명) 규모로만 검증했다. ADR-006이 언급한 "인기 콜 폭주로
  인한 DB 부하"(스레드 수십~수백 개 규모)는 이번 테스트 범위 밖이다 — 실측 필요 시 별도 부하테스트로.

## 새로 생긴 미결 사항

- 배차 실패 사유를 프론트가 안정적으로 구분하려면 현재는 `message` 문자열 파싱에 의존해야 한다.
  에러코드 필드 신설은 공용 응답 구조 변경이라 이번 이슈에서 하지 않았다 — 필요해지면 별도 이슈로.
