# 라이더 운행 기록 목록 조회 작업 기록

- 이슈: [#70](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/70)(백엔드),
  [#217](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/217) [FE-RIDE-HIST-001](프론트 화면 API 연동)
- 브랜치: `feature/70-rider-delivery-history`
- 범위: fullstack (#70 백엔드 + #217 프론트 화면을 한 브랜치에서 함께 구현)
- 작성일: 2026-08-04

## 무엇을 만들었나

라이더가 자신이 완료한 배송을 최신순 목록으로 조회하는 전용 API(`GET /api/rider/history`)를 구현하고,
운행 기록 화면(`/rider/history`)을 이 API에 연결했다. 이슈가 요구한 "본인 배정 주문을 최신순으로,
출발지·도착지·상태·시각과 함께" 중 **금액(운임)은 제외**했다 — 배송 기록과 포인트 화면이 이미
분리(커밋 `8314d68`)되어 금액은 포인트 API(`/api/rider/points/*`) 소관이기 때문이다(사람 확인).

기존에 스캐폴딩만 있던 계약 인터페이스 `RiderDeliveryHistoryApi`와 DTO를 이 결정에 맞춰 손봤고,
프론트가 임시로 쓰던 정산 조회 훅(`useGetRiderSettlements`) 의존을 걷어냈다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/rider/history?page=&size=` | 본인 배정·완료 배송을 완료 시각 최신순으로 페이지 조회 | 401(미인증) |

- 응답 항목: `deliveryId, status, itemType, pickupRoadAddress, destinationRoadAddress, straightDistanceMeters, completedAt`
- 목록: `items, page, size, totalElements`
- 기록이 없으면 빈 목록 + `totalElements=0`. 잘못된 페이지 값은 스프링 기본 바인딩이 처리.

### 화면 (#217)

- 라우트 `rider/_authed/history/` — 훅 `useGetDeliveryHistories`(Orval 생성) 소비.
- 데이터 어댑터 `-useRiderDeliveryHistory.ts`가 페이지 상태·`totalPages`·`hasPrevious/hasNext`·`totalElements`를 계산.
- 날짜 필터를 없애고 10개씩 페이지네이션으로 교체.
- **목록 카드**: 완료 날짜·시각, 출발지→도착지(타임라인), 물품 종류·이동 거리, 총 건수 뱃지를 보여준다.
  주문 번호는 작고 흐리게(`#1024`) 표시하고 "Completed" 뱃지는 뺐다. **금액(요금)은 표시하지 않는다** —
  응답에 없다(포인트 화면 소관). 요금 표시는 아래 「새로 생긴 미결 사항」 참고.

### 스키마 변경

해당 없음. `delivery_order`·`order_status_history` 등 기존 테이블만 읽는다. 마이그레이션 없음.

## 사람이 고른 선택

### 1. 프론트 연결 범위 — 풀스택

- **물었던 것**: #70 백엔드만 구현할지, 프론트 화면도 새 엔드포인트에 연결할지.
- **선택지**:
  - (A) 풀스택 — 백엔드 + 프론트 훅 교체. / 프론트 변경이 같은 브랜치에 섞임.
  - (B) 백엔드만 — 프론트 연결은 별도 이슈. / history 화면이 계속 정산 훅에 의존.
- **고른 것**: (A) 풀스택.
- **근거**: 사용자가 "history에서는 정산 조회 안 한다"며 `useGetRiderSettlements` import 제거를 강하게 요구.
  정산 조회 자체가 백엔드 미구현(`return null`)이라 현재 history 화면은 항상 비어 있었고, 전용
  엔드포인트로 갈아끼워야 실제로 동작한다.
- **영향**: 프론트 `-useRiderDeliveryHistory.ts`가 정산 도메인 훅에서 완전히 떨어져 나왔다.

### 2. 운임(금액) 포함 여부 — 제외

- **물었던 것**: history 목록 응답에 배송 요금(운임)을 넣을지.
- **선택지**:
  - (A) 제외 — 순수 배송 정보만. / 이슈 명세의 "운임" 필드를 뺌.
  - (B) 포함 — FINAL 스냅샷 `totalFare` 추가. / 요금 스냅샷 배치 조회 필요.
- **고른 것**: (A) 제외.
- **근거**: 배송 기록과 포인트 화면이 분리되어 금액 정보는 포인트 화면 소관이라는 게 사용자 확인.
  "정산 관련 정보는 /rider/points 로 분리했다"는 명시적 발언.
- **영향**: 스캐폴딩 DTO의 정산 중심 필드를 걷어냄(아래 「스스로 판단한 것」). `delivery_order`
  단일 조회로 끝나 고객 이용기록과 달리 요금 스냅샷 N+1 배치 조회가 필요 없다.

## 스스로 판단한 것

- **DTO를 정산 중심에서 배송 중심으로 재구성**: 스캐폴딩 `RiderDeliveryHistoryItemResponse`에서
  `settlementAmount`를, `RiderDeliveryHistoryListResponse`에서 `weeklySettlementTotal`을 제거했다
  (분리 결정의 결과). 대신 이슈의 "주문 상태"를 위해 `status`를 추가했다 — 운행 기록은 완료 배송만
  담아 사실상 COMPLETED 고정이지만, 계약에 명시하고 화면 뱃지를 데이터로 그리기 위함.
- **데이터 소스는 `delivery_order`의 `assignedRider + COMPLETED` 파생 조회**: 정산(`RiderSettlement`)
  기준이 아니라 주문 기준으로 조회했다. 이슈 문구("배정된 주문 조회")에 맞고, 금액을 안 담으므로
  정산 조인이 불필요하다. 정렬은 `completedAt DESC`이며 마침 인덱스
  `idx_delivery_rider_completed (assigned_rider_id, completed_at DESC)`가 그대로 받쳐 준다.
- **인터페이스에서 상세(`GET /{deliveryId}`) 메서드를 분리**: 스캐폴딩 인터페이스에 있던 상세
  메서드는 #70(목록) 범위 밖이라 인터페이스에서 뺐다. `RiderDeliveryHistoryDetailResponse` DTO는
  상세 이슈에서 재사용하도록 그대로 남겨 뒀다(현재는 호출자 0).
- **컨트롤러를 문서/구현 분리 스타일로 신규 작성**(#245): 스캐폴딩 인터페이스는 매핑을 인터페이스에
  달던 옛 스타일이었으나, 새로 만드는 만큼 `@Operation`/`@Parameter`는 인터페이스에, 매핑·바인딩
  (`@GetMapping`/`@RequestParam`/`@RequestAttribute`)은 구현체에 두었다. `operationId`를 명시해
  훅 이름을 `useGetDeliveryHistories`로 고정.
- **인증 파라미터 추가**: 스캐폴딩 인터페이스에 `AuthenticatedRider`가 빠져 있어(#55와 같은 누락)
  추가하고 `RiderWebMvcConfig`에 `/api/rider/history` 경로를 등록했다.

## 일부러 하지 않은 것

- **운행 기록 상세 API**: #70은 목록만. 상세(운임 분해·정산액·타임라인·완료 인증)는 별도 이슈. 후속: 미등록.
- **목록 카드에 요금(운임) 표시**: 응답에 요금 필드가 없어(위 결정 2) 카드에도 넣지 않았다. 표시하려면
  DTO에 `totalFare` 추가 + 재생성이 필요하다 — 「새로 생긴 미결 사항」 참고.
- **브라우저(풀스택) E2E**: 프론트 테스트 러너가 없어 하지 않았다. 백엔드 E2E + `pnpm typecheck/build`로 대신했다.
  (#217 카드 확장분도 브라우저 렌더는 미확인 — 백엔드·DB 가동 시 수동 확인 예정.)

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderDeliveryHistoryItemResponseTest` | DTO `from` 매핑(픽업/도착 뒤바뀜·상태 누락 방지) |
| 통합 | `RiderDeliveryHistoryServiceIntegrationTest` | 본인·완료만 최신순, 진행 중·타인 배송 제외, 페이지 슬라이싱, 빈 목록 |
| E2E | `RiderDeliveryHistoryE2ETest` | 로그인 라이더 200+목록, 빈 목록, 쿠키 없이 401(인터셉터 등록 회귀) |

실행 결과:

```text
./gradlew test --tests '*RiderDeliveryHistory*' → 7 tests, 0 failures (단위 1 + 통합 3 + E2E 3)
pnpm test → 18 files, 134 tests passed
pnpm typecheck / pnpm build → 통과
```

### 검증하지 못한 것

- 완료 시각이 같은 밀리초에 몰릴 때의 정렬은 통합 테스트에서 완료 사이에 소량 대기(5ms)를 넣어
  회피했다 — 실서비스의 대량 동시 완료에서의 정렬 안정성은 별도 부하 테스트 영역.
- 브라우저에서의 실제 화면 렌더는 자동 테스트로 덮지 않았다(러너 없음).

## 새로 생긴 미결 사항

- **`OrderGeoRepositoryTest`가 dev에서 컴파일 불가 상태였다**(이번에 발견·수정, #70과 무관). #339의
  rename 커밋 `1106b52`가 `RiderGeoRepository`→`OrderGeoRepository`로 메인 클래스는 내용까지 바꿨지만
  테스트 파일은 이름만 바꾸고(0 lines) 내용(클래스명·타입 참조·KEY `riders:geo`)을 안 고쳤다. CI가
  `-x test`라 컴파일 안 되는 테스트가 병합됐다. 별도 커밋으로 최소 수정(타입·KEY `order:geo`·식별자
  네이밍)했다. → CI에서 테스트를 켜지 않는 한 이런 누락이 또 새어 들어올 수 있다.
- **운행 기록 상세 API 미구현**: 프론트 `history/$deliveryId`는 정적 목업이고 백엔드 상세 엔드포인트가
  없다. 상세 이슈에서 인터페이스에 메서드를 다시 추가하고 `RiderDeliveryHistoryDetailResponse`를 재사용.
- **목록 카드 요금 표시 여부(재검토 요청)**: #217 카드 작업 중 "요금도 보여달라"는 요구가 나왔다.
  현재 `/api/rider/history`에는 금액이 없어(배송 기록/포인트 화면 분리) 못 넣는다. 넣으려면 DTO에
  배송 요금(FINAL 스냅샷 `totalFare`) 추가 + 재생성이 필요하고, 이는 "history=금액 없음" 결정을
  일부 되돌리는 것이다(운임=배송 속성 vs 정산액=포인트 원장의 구분). 사람 확인 대기 중.
