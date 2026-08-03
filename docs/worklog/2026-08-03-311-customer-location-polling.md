# 고객 위치 조회 Polling API (부하테스트용) 작업 기록

- 이슈: [#311](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/311)
- 브랜치: `feature/311-customer-location-polling`
- 범위: backend
- 작성일: 2026-08-03

## 무엇을 만들었나

이슈가 요구한 그대로 `GET /api/customer/deliveries/{deliveryId}/location`을 구현했다. 인가·소유권
판정은 SSE(`/tracking/stream`, #77)·추적 스냅샷(`/tracking`, #79)과 완전히 같은
`DeliveryTrackingAccessService.authorizeTracking`을 그대로 재사용했고, 신규로 만든 것은 딱 두 가지다
— Redis에서 라이더 최신 위치를 읽는 `RiderLocationRepository.find`/`decode`(#317이 호출자 없이
비워 둔 자리), 그리고 그 조회 실패를 위장하지 않고 503으로 바꾸는 얇은 서비스 하나.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/customer/deliveries/{deliveryId}/location` | 라이더 위치 폴링 조회(부하테스트용) | 404(없음·타인 것), 409(추적 불가 상태), 503(Redis 조회 실패) |

### 화면

해당 없음 — 이슈 완료 조건에 "프론트 연동은 범위 밖"이 명시돼 있다. k6 등 부하테스트 도구가 이
엔드포인트를 직접 호출한다.

### 스키마 변경

해당 없음 — Redis 값 형식(`RiderLocationRepository.encode`)은 그대로 두고 읽기만 추가했다.

## 사람이 고른 선택

### 1. 이슈가 요구한 "SSE `TrackingInitPayload`와 응답 DTO 공유"를 어떻게 처리할까

- **물었던 것**: 이슈 본문은 폴링 응답이 SSE의 `TrackingInitPayload`(orderId, status, location)를
  재사용/공유해야 한다고 적혀 있는데, 실제 코드에는 그 타입이 없다. `git log -S TrackingInitPayload`로
  확인한 결과 #77(커밋 `fc640ee`)에서 만들어졌다가 위치 추적 단순화 PR #289(커밋 `e04b35a`, "기존
  location 구현 제거")에서 삭제됐고, #317이 SSE Pub/Sub 팬아웃을 되돌릴 때도 이 부분은 복원하지
  않았다. 지금 `CustomerTrackingStreamController.subscribeTracking`은 구독 직후 `"connected"`
  코멘트만 보내고 위치 스냅샷을 담은 init 이벤트 자체가 없다. 이 괴리를 어떻게 다룰지 확인이 필요했다.
- **선택지**:
  - (A) 폴링 전용 DTO를 새로 만든다 — SSE init 스냅샷 기능 자체는 이번 이슈 범위 밖으로 두고, 나중에
    그 기능이 다시 생기면 그때 재사용 여부를 판단한다. 이슈의 완료 조건(엔드포인트+테스트+문서)만
    충족.
  - (B) SSE에도 init 이벤트를 함께 복원해 진짜로 DTO를 공유한다 — 이슈에 없는 SSE 컨트롤러 동작
    변경이 포함되고, 기존 SSE E2E 테스트(`CustomerTrackingStreamE2ETest`, `TrackingChannelTest` 등)에
    영향을 줄 수 있어 범위가 커진다.
- **고른 것**: (A)
- **근거**: 사람이 git log로 삭제 사실을 먼저 확인한 뒤 "폴링 전용 DTO를 만들어야겠다"고 판단했다 —
  이슈 본문이 삭제 전 코드 상태를 전제로 쓰여 있었을 뿐, SSE 동작을 바꾸라는 요구가 아니었다는
  것이 확인됐다.
- **영향**: `location/dto/CustomerDeliveryLocationResponse`는 폴링 arm 전용이다. SSE에 init 스냅샷이
  다시 생기는 이슈가 열리면, 그때 이 DTO를 재사용할지 새로 만들지 판단해야 한다 — 지금은 필드가
  같다는 것 외에 강제하는 계약이 없다.

## 스스로 판단한 것

- **패키지 위치를 `order`가 아니라 `location`으로 잡았다**: SSE(`/tracking/stream`)도 URL은
  `/api/customer/deliveries/**` 아래지만 패키지는 `location`이다 — 위치 데이터를 소유한 애그리거트가
  location 도메인이라는 `backend.md` 원칙을 그대로 따랐다. `/tracking`(스냅샷, #79)만 `order`에 있는
  이유는 그쪽이 상태 타임라인·라이더 연락처·요금처럼 order가 소유한 데이터이기 때문이고, 이번
  엔드포인트는 그 반대다.
- **컨트롤러 Api/구현 분리 스타일을 `CustomerTrackingStreamApi`(매핑을 인터페이스에 둔 옛 스타일)가
  아니라 `CustomerPointApi`/`CustomerPaymentController`(매핑을 구현체에만 두는 새 스타일)로
  맞췄다**: `backend.md`가 "새 컨트롤러는 처음부터 위 형태로 만든다"고 명시했고, `location` 패키지
  안에 옛 스타일 파일이 있다고 새 파일까지 그 스타일을 따라야 할 이유는 없었다.
- **Redis 조회 실패(연결 끊김 등)를 503으로 바꾸는 위치를 리포지토리가 아니라 서비스에 뒀다**:
  `RiderLocationRepository`의 다른 메서드(`saveIfNewer`)는 예외를 그대로 던지고 호출자가 판단하게
  하는 관례라, `find`도 같은 관례를 따르고 "위치 없음으로 위장하지 않는다"는 판단은 이 이슈의 서비스
  계층(`CustomerLocationQueryService`) 책임으로 뒀다.
- **`decode`가 형식이 깨진 값을 만나면 예외 대신 `null`(→ "위치 없음")을 돌려준다**: `saveIfNewer`의
  Lua가 손상된 값을 다음 정상 쓰기로 자연히 덮는다는 기존 정책과 대칭을 맞췄다. 이건 Redis
  "장애"가 아니라 자연히 회복되는 상태라고 보고 503으로 격상하지 않았다 — 503은 연결 실패처럼
  진짜 조회 자체가 안 되는 경우로 한정했다.
- **`LocationPayload`에 `@Schema` 설명을 추가했다**: 이 record는 지금까지 내부 전용(Redis 인코딩,
  라이더 위치 갱신 요청 변환)이라 문서 애노테이션이 없었는데, 이번에 처음으로 고객 응답 스키마에
  노출되므로 최소한의 설명을 달았다. 기존 사용처(`RiderLocationUpdateRequest.toLocationPayload()`
  등)에는 영향 없는 순수 애노테이션 추가다.

## 일부러 하지 않은 것

- **SSE init 스냅샷 복원**: 위 결정 1번 참고. 필요해지면 별도 이슈로 연다.
- **프론트 Orval 연동**: 이슈 완료 조건에 명시적으로 범위 밖. `@Hidden` 처리는 하지 않았다 — SSE와
  달리 이 엔드포인트는 실제 REST 계약이라 나중에 프론트가 써도 무방하고, k6도 `/v3/api-docs` 스키마를
  참고할 수 있는 편이 낫다고 판단했다.
- **연결 수 제한·레이트 리밋**: 이슈 요구사항에 없다. 폴링 arm의 용량 자체를 재는 것이 부하테스트
  목적이라 여기서 제한을 걸면 비교가 왜곡된다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderLocationRepositoryTest$Decode` | `encode`/`decode` 왕복, 필드 개수 불일치·손상값에서 `null` |
| 단위 | `CustomerLocationQueryServiceTest` | 인가 게이트 위임(404/409 그대로 전파), 위치 있음/없음 응답 분기, Redis 예외 → 503 변환 |
| 통합 | `RiderLocationRepositoryIntegrationTest$Find` | 실제 Redis에서 없음/있음/손상값/덮어쓰기 후 최신값 읽기 |
| E2E | `CustomerLocationPollingE2ETest` | 위치 있음 200, 위치 없음 200(location null), 쿠키 없음 401, 라이더 세션 401, 타인 배송 404, WAITING/COMPLETED 409 |

실행 결과:

```text
./gradlew test --tests 'com.turkey.quick.location.*'  → BUILD SUCCESSFUL
./gradlew test (전체)                                   → BUILD SUCCESSFUL, 502 tests, 0 failures
curl localhost:8080/v3/api-docs                        → getCustomerDeliveryLocation 확인,
  CustomerDeliveryLocationResponse/LocationPayload 스키마 정상 생성
```

### 검증하지 못한 것

- **Redis 실제 연결 장애 → 503**은 통합/E2E로 재현하지 않았다(컨테이너를 중간에 죽여야 하는데 이번
  이슈 범위에서는 비용 대비 가치가 낮다고 판단). Mockito로 `RiderLocationRepository.find`가 던지는
  `RuntimeException`을 503으로 바꾸는 것만 단위 테스트로 확인했다.
- 부하테스트 자체(k6 실행, SSE와의 실제 용량 비교)는 이 이슈 범위 밖이다 — #270/#259가 그 몫이다.

## 새로 생긴 미결 사항

- 폴링 응답과 SSE init 스냅샷의 DTO 공유 여부는 SSE에 init 이벤트가 다시 생기는 시점에 재판단해야
  한다(CLAUDE.md에도 같은 내용 추가 예정).
