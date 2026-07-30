# [RIDE-QUICK-003] 퀵 요청 상세사항 보기 작업 기록

- 이슈: [#57](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/57)
- 브랜치: `feature/57-rider-quick-request-detail` (base: `feature/55-rider-quick-request-list`, #55 미머지 상태에서 이어 작업)
- 범위: backend
- 작성일: 2026-07-30

## 무엇을 만들었나

AVAILABLE 라이더가 배차 대기(WAITING) 배송요청 하나의 상세정보를 조회하는 API를 구현했다. #55에서 만든
`RiderDeliveryRequestController`의 스텁 메서드(`getDeliveryRequest`)를 채웠고, 같은 서비스 클래스
(`RiderDeliveryRequestService`)에 메서드를 추가하는 방식으로 목록 조회와 로직을 공유했다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/rider/requests/{deliveryId}` | AVAILABLE 라이더에게 WAITING 배송요청 하나의 상세(주소·거리·소요시간·운임) 반환 | 401 미인증, 403 라이더 상태가 AVAILABLE 아님, 404 존재하지 않거나 WAITING이 아닌 주문 |

### 화면

해당 없음 — #55와 같은 이유로 backend 범위로 판정(이슈 본문에 화면 요구사항 없음).

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. "물품 크기·무게·수량" 정보 제공 범위

- **물었던 것**: 이슈 본문은 "물품 종류·크기·무게·수량"을 요구하는데, `delivery_order` 도메인 모델과
  ERD 어디에도 크기/무게/수량 컬럼이 없음(`itemType` 열거형만 존재: DOCUMENT/SMALL_PARCEL/MEDIUM_PARCEL/
  LARGE_PARCEL/FOOD). 어느 쪽으로 할지.
- **선택지**:
  - (A) `itemType`만 반환 — 스키마 변경 없이 진행, #55와 일관성 유지 / 무게·수량은 이 이슈 범위에서 제공 못 함
  - (B) 스키마에 weight/quantity 컬럼 추가 — 이슈 문구와 정확히 일치 / Flyway 마이그레이션 + 주문 생성
    (REQ-ORD-002, 아직 미구현)에서도 이 값을 받아 저장해야 해서 범위가 이 이슈를 넘어 REQ-ORD-002까지
    조율이 필요함
- **고른 것**: (A)
- **근거**: #55와 일관성을 유지하고, 아직 존재하지 않는 주문 생성 플로우까지 건드리는 스키마 변경을
  이 조회 전용 이슈에서 하지 않기로 함.
- **영향**: 라이더 화면에서 물품 무게·수량을 보여줘야 한다면 별도 이슈(스키마 변경 + REQ-ORD-002 연동
  포함)로 다시 논의해야 한다. 아래 「새로 생긴 미결 사항」에 등록.

## 스스로 판단한 것

- **`RiderDeliveryRequestApi.getDeliveryRequest`에 `AuthenticatedRider` 파라미터 추가**: #55에서 이미
  확인된 계약 누락(4개 메서드 전부 라이더 식별 파라미터가 빠져 있었음)을 이번에 쓰는 메서드에 적용했다.
  #55 때와 같은 패턴이라 다시 게이트에서 묻지 않고 바로 적용함.
- **`RiderDeliveryRequestDetailResponse`에 `estimatedMinutes` 필드 추가**: 이슈 본문의 "예상 소요시간"이
  이미 커밋된 DTO에 빠져 있었다. `DeliveryService.estimateMinutes(int)`(REQ-ORD-001에서 구현된 기존
  메서드)를 그대로 재사용해 저장된 직선거리로부터 계산했다 — 별도 저장 없이 매번 계산.
- **404 통합 처리**: "존재하지 않는 주문", "이미 배차된 주문", "취소된 주문"을 구분하지 않고 모두 404로
  응답한다. 이미 커밋된 계약 주석("이미 다른 라이더가 가져갔으면 404다")과 같은 방향이고, 라이더 입장에서는
  "지금 이 콜을 수행할 수 있는가"만 의미가 있어 사유를 구분해 알려줄 이유가 없다고 판단했다.
- **상세 주소 비우기**: `RiderDeliveryRequestDetailResponse`의 기존 Javadoc("상세 주소는 수락 전까지
  비어 있다")을 그대로 구현했다 — `AddressResponse`를 만들 때 `detailAddress`를 항상 `null`로 채운다.
  이건 이미 결정된 정책을 코드로 옮긴 것이라 새로 판단한 게 아니다.
- **`OrderFareSnapshotRepository`에 단건 조회 메서드 추가**: 기존 `findByOrder_IdInAndFareType`(배치용,
  #55)과 별도로 `findByOrder_IdAndFareType`(단건, `Optional` 반환)을 추가했다. 상세 조회는 주문 하나만
  다루므로 배치용 메서드에 `List.of(id)`를 넣어 재사용하는 것보다 단건 전용 메서드가 더 읽기 쉽다고 판단.

## 일부러 하지 않은 것

- **물품 무게·수량 노출**: 위 「사람이 고른 선택」 1번 참고. 스키마 변경 없이 `itemType`만 반환.
- **`acceptDeliveryRequest`(#56)·`skipDeliveryRequest`**: 여전히 스텁(`return null`)으로 남겨둠 — 각자
  이슈 범위.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderDeliveryRequestServiceTest`(`GetDeliveryRequestTest` 중첩 클래스 추가) | AVAILABLE 아님 403, 존재하지 않는 주문 404, WAITING 아닌 주문 404, 스냅샷 누락 시 정합성 오류, 정상 조회 시 상세주소 null·소요시간 계산·운임 매핑 |
| 통합 | `RiderDeliveryRequestServiceIntegrationTest`(4개 케이스 추가) | 실제 MySQL로 WAITING 상세 조회(상세주소 null 확인), 배차된 주문 404, 존재하지 않는 주문 404 |
| E2E | `RiderDeliveryRequestE2ETest`(4개 케이스 추가) | AVAILABLE 라이더 200(상세주소 null 확인 포함), 세션 없음 401, AVAILABLE 아님 403, 존재하지 않는 주문 404 |

실행 결과:

```text
./gradlew test → BUILD SUCCESSFUL, 229 tests, 0 failures, 0 errors
  (#55/#57 누적 29개: 단위 14 + 통합 8 + E2E 8. 기존 대비 신규 13개 이번 이슈, 회귀 없음)
pnpm typecheck → 실행하지 않음(백엔드 전용 이슈, 프론트 파일 변경 없음)
```

### 검증하지 못한 것

- #55와 동일한 한계: 브라우저 E2E 없음(화면 이슈 아님), Redis 컨테이너는 이 세션에서 새로 설치한
  colima로 띄워 실제 검증함.
- "이미 배차된 주문" 404는 실제 배차 확정 로직(#56)이 아직 없어, 통합 테스트에서
  `DeliveryOrder.assign()` 도메인 메서드를 직접 호출해 상태만 재현했다. #56 구현 후 실제 `accept` 흐름과
  end-to-end로 다시 확인이 필요하다.

## 새로 생긴 미결 사항

- 라이더 콜 상세(`GET /api/rider/requests/{deliveryId}`, #57)가 물품 무게·수량을 제공하지 못함 —
  `delivery_order`에 관련 컬럼이 없기 때문. 화면에서 실제로 필요해지면 스키마 변경(Flyway 마이그레이션)과
  주문 생성(REQ-ORD-002) 쪽 값 저장까지 함께 논의해야 한다.
