# 라이더 홈 화면 조회(운행 상태 조회) 작업 기록

- 이슈: [#53](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/53) [RIDE-HOME-001] 라이더 홈 화면 조회
- 브랜치: `feature/53-rider-home-operating-status`
- 범위: backend
- 작성일: 2026-07-30

## 무엇을 만들었나

라이더 홈 화면이 소비할 **운행 상태 조회 API**를 백엔드에 구현했다. 이슈의 처리 흐름
①세션 확인 ②운행 상태 조회 ③진행 중 배송 존재 확인 ④메뉴 정보 반환 중 ①~③이 이 API 한 번에
대응된다(④의 "운행 기록 보기/운행하기" 메뉴는 정적이라 프론트 몫, FE 이슈 #213). 화면 자체와
"운행하기" 상태 전이는 별도 이슈다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/rider/operating-status` | 현재 운행 상태 + 상태 변경 시각 + 진행 중 배송 식별자 조회 | 401 (미로그인·무효 세션) |

응답 `RiderOperatingStatusResponse(operatingStatus, statusChangedAt, currentDeliveryId)`.
`currentDeliveryId`는 진행 중 배송이 없으면 `null`(BUSY 가 아니면 항상 null).

### 화면

해당 없음(백엔드 이슈). 프론트 홈 화면은 #213 [FE-RIDE-HOME-001].

### 스키마 변경

해당 없음. `rider_profile`(운행 상태·시각), `delivery_order`(배차) 모두 기존 스키마로 충분하다.

## 사람이 고른 선택

### 1. #53과 #54(운행 상태 변경)를 어떻게 나눌지

- **물었던 것**: `RiderOperatingStatusApi`가 GET(조회, #53)과 PATCH(콜 받기/운행 종료, #54)를 한
  인터페이스에 번들로 선언해 뒀는데, '홈 화면 조회'인 #53에서 어디까지 구현할지.
- **선택지**:
  - (A) GET만 — #53은 조회 이슈. PATCH는 별도 관심사(전이·409·운행 종료 후 Redis 위치 정리).
  - (B) GET+PATCH 한 번에 — 홈 조회와 상태 변경을 #53에서 함께.
- **고른 것**: 처음엔 (B)로 "#54가 운행 상태 변경이니 한 번에 구현하고 커밋으로 구분"하기로 했다가,
  진행 중 **"#53·#54를 각자 브랜치로 나눠 구현"으로 변경**. 이 브랜치는 #53(GET)만 담고, #54(PATCH)는
  별도 브랜치에서 구현한다.
- **근거**: 사람이 이슈 단위로 브랜치·PR을 분리하길 원했다.
- **영향**: `/api/rider/operating-status` 경로 인터셉터 등록은 이 브랜치에서 해 두었으므로, #54는 같은
  경로의 PATCH(`changeOperatingStatus`)만 채우면 된다.

## 스스로 판단한 것

- **인터페이스/구현 분리는 payment 컨벤션(Discussion #245)을 GET·PATCH 양쪽에 적용**: 기존
  `RiderOperatingStatusApi`는 GET(조회)과 PATCH(`changeOperatingStatus`, #54)를 함께 선언한 계약이다.
  명세 인터페이스에는 문서화 어노테이션(`@Tag`·`@Operation`·`@ApiResponses`)만 두고, 경로·메서드
  매핑(`@RequestMapping`·`@GetMapping`·`@PatchMapping`)과 바인딩·검증(`@RequestAttribute`·`@Valid`·
  `@RequestBody`)은 전부 구현체(`RiderOperatingStatusController`)로 옮겼다(`CustomerPointApi`/
  `RiderPaymentController` 형태). 인터페이스에 Bean Validation 제약을 두지 않아 오버라이드 충돌
  (HV000151)이 원천 차단된다.
- **PATCH(`changeOperatingStatus`)는 이번 이슈 범위 밖이라 `return null` 스텁으로 둔다**: #53 은 조회만
  구현하므로 GET 만 실제 로직을 채우고, PATCH 는 payment 의 미구현 엔드포인트(`RiderPaymentController`)와
  같이 구현체에서 `return null` 로 두고 #54 가 채운다. @Operation·@ApiResponses 문서 내용은 dev 그대로
  보존했다. **그 결과 PATCH 는 return-null 상태로 매핑된다**(payment 의 미구현 엔드포인트와 같은 상태) —
  #54 가 실제 전이·409·위치 정리를 채운다.
- **진행 중 배송은 식별자(존재 여부)만**: 이슈 ③이 "존재 여부 확인"이라 `currentDeliveryId` 하나로
  충분하다. 진행 배송 **상세 조회·화면 복구**는 #86 [RIDE-QUICK-013] 몫이라 여기서 상세를 싣지 않았다.
- **진행 중 배송 조회는 #78 것을 재사용**: 처음엔 `OrderStatus.riderActiveStatuses()` + 전용 리포지토리
  쿼리를 새로 만들었으나, 리베이스 과정에서 #77/#78(고객 위치 추적 SSE)이 이미 같은 것을 도입했음을
  발견했다 — `OrderStatus.trackableStatuses()`(ASSIGNED~DELIVERING, `active_rider_id` 생성 컬럼과
  일치)와 `DeliveryOrderRepository.findInProgressByRiderId(riderId, statuses)`(라이더당 진행 중 1건,
  `uk_delivery_active_rider` 로 보장). "라이더의 진행 중 배송 1건"이라는 개념이 완전히 같아 내가 만든
  것은 순수 중복이었다. 그래서 도메인·리포지토리 추가를 모두 버리고 그 둘을 재사용한다(사람 확인).
  같은 목록·조회를 두 곳에 두면 갈려서 "BUSY 인데 진행 배송이 안 잡히는" 버그가 생긴다는 것은 #77
  주석도 명시한다.
- **`statusChangedAt`을 위해 서비스에서 프로필 재조회**: 세션 인터셉터가 넘기는 `AuthenticatedRider`에는
  운행 상태만 있고 변경 시각이 없어, 조회 서비스에서 `RiderProfile`을 다시 읽어 상태·시각을 한 스냅샷으로
  담았다.
- **`@Operation(operationId = "getRiderOperatingStatus")` 명시**: 생략 시 springdoc이 메서드명을 쓰고
  동명 충돌에 `_1`을 붙여 프론트 훅이 액터를 구분 못 하는 회귀(#194)를 막는다.

## 일부러 하지 않은 것

- **PATCH 운행 상태 변경(콜 받기/운행 종료)**: 별도 브랜치·이슈 #54. — 후속: #54
- **`RiderLocationStore.delete`(운행 종료 시 Redis 최신 위치 즉시 제거)**: 운행 종료가 #54에 있으므로
  그 브랜치에서 추가한다. 이 브랜치에는 넣지 않았다. — 후속: #54
- **프론트 홈 화면 및 "운행하기" 진입**: FE 이슈. — 후속: #213
- **진행 배송 상세·화면 복구**: — 후속: #86

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 통합 | `rider/service/RiderOperatingStatusQueryServiceIntegrationTest` | 실제 MySQL에서 BUSY 라이더→진행 배송 식별자 반환, AVAILABLE·UNAVAILABLE→null |
| E2E | `rider/controller/RiderOperatingStatusE2ETest` | 실제 HTTP: BUSY→상태+식별자, AVAILABLE→상태만, **쿠키 없음→401**(인터셉터 등록 누락 회귀 방지) |

**단위 테스트는 두지 않았다.** 새로 만든 도메인 로직이 없다 — 상태 집합(`trackableStatuses`)과 진행 중
배송 조회(`findInProgressByRiderId`)는 #77/#78 것을 재사용하고 그쪽 테스트가 이미 고정한다. 이 이슈에
남는 것은 프로필·주문을 합쳐 응답으로 매핑하는 얇은 읽기뿐이라, 통합에서 실제 매핑을 검증하는 것이
같은 것을 두 번 검증하지 않으면서 더 실질적이다.

실행 결과:

```text
./gradlew test → 신규 6개(통합 3 + E2E 3) 전체 통과.
전체 스위트(483) 중 #78 의 SSE 팬아웃(2인스턴스 Pub/Sub) 테스트가 타이밍으로 간헐 실패하나
격리 실행 시 통과하며 #53(read-only 조회)과 무관하다 — 다른 담당 영역이라 넘어감.
```

### 검증하지 못한 것

- 이 이슈는 Redis를 직접 쓰지 않아(위치 저장/삭제 없음) Redis 관련 미검증 항목은 해당 없음.
- 전체 스위트의 SSE 팬아웃 멀티인스턴스 테스트(#78)는 간헐 실패가 있으나 이 이슈 범위 밖이다.

## 새로 생긴 미결 사항

- **`dev` 브랜치의 테스트 컴파일이 깨져 있었다**(이 이슈와 무관, 별도 발견 → 별도 브랜치
  `fix/254-signup-e2e-real-redis`, PR [#269](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/pull/269)).
  `./gradlew test`가 컴파일 단계에서 실패해 어떤 백엔드 테스트도 돌릴 수 없었다.
  경위: #254(`bdd89a9`)가 통합·E2E 를 실제 Redis 로 전환하며 signup E2E 의 인메모리
  `VerificationCodeStore` `@Primary` 대체를 **제거**했는데, #241(bdd89a9 이전 기반)이 그 블록을
  **되살렸고**, 이후 머지(#253 경유 `0356ee7`)가 import 만 떨궈 "사용은 있는데 import 없음" 상태가
  됐다(#263/#264가 그 위에 병합됨). import 를 되살리는 대신 **#254 의도대로 인메모리 대체를 마저
  제거**하는 방향으로 해소했다(사람 확인). CI 가 이 컴파일 회귀를 못 잡은 경위(경로 필터/백엔드
  테스트 미실행 여부) 확인이 필요하다.
