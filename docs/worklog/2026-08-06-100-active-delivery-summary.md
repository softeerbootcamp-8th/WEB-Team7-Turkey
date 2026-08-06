# 진행 중 배송 요약 조회 작업 기록

- 이슈: [#100](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/100)
- 브랜치: `feature/100-active-delivery-summary`
- 범위: backend
- 작성일: 2026-08-06

## 무엇을 만들었나

고객 홈(#208)이 "진행 중 배송이 있는지"를 알기 위한 전용 조회 API 를 만들었다. 이슈 #100 의
처리 흐름(세션 확인 → WAITING~DELIVERING 본인 주문 → 요약 반환, 없으면 빈 결과)을 그대로 구현했다.

핵심은 **새 쿼리를 만들지 않았다는 것**이다. `DeliveryOrderRepository.findActiveByCustomerId`
(생성 컬럼 `active_customer_id` + UNIQUE, #42 지연 만료가 쓰던 것)가 "진행 중"의 정의와 ≤1건
보장을 이미 갖고 있어, 그 위에 서비스·DTO·컨트롤러 메서드만 얇게 얹었다.

프론트 연동(#208)은 이 PR 범위 밖이다 — 아래 「일부러 하지 않은 것」.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/customer/deliveries/active` | 진행 중(WAITING~DELIVERING) 배송 요약 조회 | 401(미로그인). 없으면 200 + data=null |

응답 DTO `ActiveDeliveryResponse`: deliveryId, status, pickupRoadAddress, destinationRoadAddress,
requestedAt. 진행 중 배송이 없으면 `ApiResponse.ok(null)`(data=null, success=true).

### 화면

해당 없음(백엔드 범위). 프론트 소비는 #208.

### 스키마 변경

해당 없음. `active_customer_id` 생성 컬럼은 이미 존재한다(#42).

## 사람이 고른 선택

### 1. 응답 DTO — 전용 경량 DTO vs 기존 DeliverySummaryResponse 재사용

- **물었던 것**: 홈 요약이 "간단한 정보"로 요청됐는데, 응답을 목록 항목 DTO 로 재사용할지 새로 만들지.
- **선택지**:
  - (A) 전용 경량 DTO — 요금 없음, 스냅샷 조회 불필요, 스냅샷 불변식 500 위험 없음 / DTO 하나 늘어남
  - (B) `DeliverySummaryResponse` 재사용 — DTO 절감 / 요금 표시 위해 fare 스냅샷 조회가 붙고,
    스냅샷 없으면 500(불변식 위반)이라 홈 진입이 그 위험을 짊
- **고른 것**: (A) 전용 경량 DTO
- **근거**: 사람 확인(2026-08-06). "간단한 정보" 요구에 맞고, 홈 진입이 요금 스냅샷 불변식에
  묶일 이유가 없다.
- **영향**: 홈 요약에는 요금이 없다. 나중에 요금이 필요해지면 이 DTO 에 필드를 더하거나(추가만),
  그때 스냅샷 조회를 붙일지 재판단한다.

### 2. 진행 중 배송이 없을 때의 응답 표현

- **물었던 것**: "빈 결과"를 어떻게 표현할지.
- **선택지**:
  - (A) 200 + data:null — ApiResponse 봉투 유지, Orval 훅 그대로, 프론트가 null 체크로 분기 / 없음
  - (B) 204 No Content — 의미는 명확하나 ApiResponse<T> 봉투 관례와 어긋나고 프론트 분기가 번거로움
- **고른 것**: (A) 200 + data:null
- **근거**: 사람 확인(2026-08-06). 저장소의 `ApiResponse` 관례와 일치하고 프론트 소비가 단순하다.
- **영향**: 프론트(#208)는 `data == null` 로 "진행 중 배송 없음"을 판정한다.

## 스스로 판단한 것

- **경로를 `/api/customer/deliveries/active`(정적 세그먼트)로 잡았다** — 근거: 형제 `/{deliveryId}`
  보다 정적 경로가 먼저 매칭돼 충돌이 없다. E2E 가 "진행 중 배송이 실제 요약으로 돌아오는지"로
  라우팅 우선순위를 회귀 고정한다("active" 가 Long 파싱으로 새면 그 테스트가 깨진다).
- **인터셉터에 경로를 새로 등록하지 않았다** — 근거: `CustomerWebMvcConfig` 의 기존
  `/api/customer/deliveries/*` 패턴이 `/active` 를 이미 덮는다(quote 만 제외). 쿠키 없이 401 이
  나오는지를 E2E 로 확인해 이 가정을 고정했다.
- **operationId 를 `getCustomerActiveDelivery` 로 명시했다** — 근거: 액터를 이름에 넣는 관례(#194).
  Orval 훅이 `useGetCustomerActiveDelivery` 가 된다.
- **`CustomerDeliveryApi` 의 옛 형식(인터페이스에 매핑)을 따랐다** — 근거: 이 파일이 이미 그
  형식이고, 새 분리 형식으로 옮기는 건 이 이슈 범위 밖이다(`references/backend.md` 지침).

## 일부러 하지 않은 것

- **프론트 연동(#208)**: 이슈 #100 은 backend 범위다 — 이유: 사람이 정한 PR 분리(백엔드/프론트 분리,
  2026-08-06) — 후속: #208 에서 Orval 재생성(`useGetCustomerActiveDelivery`) 후 `customer/index`
  에서 소비(진행 중이면 퀵부르기 숨김 + 간단 정보 + tracking 이동).
- **요금·완료시각 등 추가 필드**: "간단한 정보" 범위로 좁힘 — 이유: 위 선택 1 — 후속: 필요 시 필드 추가.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `order/service/ActiveDeliveryQueryServiceTest` | Optional.of → 요약 매핑, Optional.empty → null |
| E2E | `order/controller/CustomerActiveDeliveryE2ETest` | 쿠키 없음 401, 진행 중 있으면 200+요약(라우팅 우선순위 포함), 없으면 200+data null |
| 계약 | `common/openapi/OpenApiOperationIdE2ETest` | operationId 누락·중복·`_1` 접미사 없음 |

실행 결과:

```text
./gradlew test --tests '*ActiveDeliveryQueryServiceTest' --tests '*CustomerActiveDeliveryE2ETest' --tests '*OpenApiOperationIdE2ETest' → BUILD SUCCESSFUL
./gradlew test --tests 'com.turkey.quick.order.controller.*' → BUILD SUCCESSFUL (형제 라우트 회귀 없음)
```

### 검증하지 못한 것

- 실제 브라우저 화면 확인은 #208(프론트) 몫이라 이 PR 에서는 하지 않았다.

## 새로 생긴 미결 사항

- 없음. (조회 상한·페이지네이션 같은 이슈는 이 API 가 최대 1건만 반환하므로 해당 없음.)
