# 라이더 운행 기록 상세 조회 작업 기록

- 이슈: [#71](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/71) (RIDE-HISTORY-002, 백엔드)
- 브랜치: `feature/71-rider-delivery-history-detail`
- 범위: backend
- 작성일: 2026-08-05

## 무엇을 만들었나

라이더가 자신이 완료한 배송 한 건의 전체 상세(출발지·도착지·물품, 상태 전이 타임라인, 확정 운임,
정산 내역, 완료 인증)를 조회하는 API(`GET /api/rider/history/{deliveryId}`)를 구현했다. #70(목록)이
상세용으로 미리 남겨 둔 응답 DTO `RiderDeliveryHistoryDetailResponse`(호출자 0 스텁)를 소비하고,
목록 인터페이스에서 빠져 있던 상세 `@Operation` 메서드·컨트롤러 핸들러·서비스 조립 메서드를 채웠다.
고객 배송 상세(`DeliveryDetailQueryService`, #46)가 사실상 그대로 템플릿이 됐다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/rider/history/{deliveryId}` | 본인이 완료한 배송의 상세(운임·정산·타임라인·완료 인증) | 401(미인증) / 404(없음·타인 것·미완료) / 500(불변식 위반) |

- 조회 게이트: 배정 조건 + 상태 COMPLETED 를 쿼리에 넣어, 없음·타인 것·진행 중을 **같은 404** 로 응답한다.
- finalFare 는 FINAL 스냅샷, settlementAmount/settledAt 은 `rider_settlement` 단건, steps 는
  `delivery_order` 시각 컬럼 파생, proofValue 는 PHOTO 면 저장소가 해석한 경로.
- 인증 경로는 `RiderWebMvcConfig` 의 `/api/rider/history/**` 가 이미 커버해 별도 등록이 없었다.

### 화면

해당 없음. 프론트 `rider/history/$deliveryId` 정적 목업 연결은 범위에서 제외(아래 「사람이 고른 선택」 1).

### 스키마 변경

해당 없음. 기존 `delivery_order`·`order_fare_snapshot`·`rider_settlement`·`delivery_proof` 만 읽는다.

## 사람이 고른 선택

### 1. 프론트 연결 범위 — 백엔드만

- **물었던 것**: 프론트 상세 화면(정적 목업)을 이 작업에서 새 API 에 연결할지, 백엔드만 할지.
- **선택지**:
  - (A) 백엔드만 — 상세 API + 백엔드 테스트만. / 프론트 목업→실데이터 연결은 별도 이슈.
  - (B) 풀스택 — #70 과 대칭으로 화면까지 연결. / 브랜치에 프론트 변경이 섞이고 E2E 범위가 커짐.
- **고른 것**: (A) 백엔드만.
- **근거**: 사용자가 "백엔드만 (#71 순수)" 선택. 이슈 본문이 조회 API 만 명시하고 화면은 비고에만 언급.
- **영향**: 프론트 `history/$deliveryId` 는 여전히 목업이다. 연결은 별도 이슈에서(후속 미등록).

### 2. 상세 응답에 정산액 포함 — 포함

- **물었던 것**: 상세 응답에 정산액(`settlementAmount`/`settledAt`)을 담을지. #70 목록이 "금액은 포인트
  API 소관"이라 뺐던 것과 방향이 갈리는 지점이었다.
- **선택지**:
  - (A) 포함 — 이슈·DTO 설계대로 운임+정산을 함께. / `rider_settlement` 단건 조회 추가 필요.
  - (B) 제외 — 목록과 동일 정책, 운임만. / 이슈 명세의 "정산 내역"을 빼고 기존 DTO 필드를 다시 제거해야 함.
- **고른 것**: (A) 포함.
- **근거**: 사용자가 "포함 (이슈·DTO 설계대로)" 선택. 이슈 #71 이 "확정 운임과 정산 내역을 조회"로 명시하고,
  스텁 DTO Javadoc 도 "운임+정산 함께 담는다"로 설계돼 있었다. 상세는 "왜 이 금액인지"를 설명하는
  정산 확인 화면이라 목록과 정책이 갈린다는 점을 확정했다.
- **영향**: `RiderSettlementRepository.findByOrder_Id` 를 새로 추가했다. 상세는 목록과 달리 금액 소관
  분리 원칙의 예외가 됐고, 그 근거를 서비스·인터페이스 Javadoc 에 남겼다.

## 스스로 판단한 것

- **상태 COMPLETED 를 조회 쿼리 조건에 넣음**(소유만 보고 상태는 서비스에서 검증하는 대신):
  `findByIdAndAssignedRider_MemberIdAndStatus(id, riderId, COMPLETED)`. 운행 기록은 완료 배송의
  기록이라 진행 중 주문 id 는 404 가 자연스럽고 — 진행 중 상세는 #86 소관 — 이 조건이 곧 아래 두
  조회(FINAL 스냅샷·정산)의 **존재 불변식**을 보장한다. 없음/타인 것/미완료를 같은 404 로 묶은 것은
  고객 상세와 같은 이유(존재 여부 비노출).
- **없어야 할 데이터는 500**: FINAL 스냅샷·정산이 없으면 400 이 아니라 500 이다. COMPLETED 면 완료
  트랜잭션이 둘 다 만들었어야 하므로 클라이언트가 고칠 수 없는 데이터 손상이다(고객 상세와 동일 판단).
- **타임라인·운임 변환을 복제 대신 공용 팩토리로**: 상태 타임라인 파생은 이미
  `DeliveryTrackingQueryService.steps`·`DeliveryDetailResponse.steps` 두 곳에 복제돼 있었다. 세 번째로
  복제하지 않고 `DeliveryStatusStepResponse.timelineOf(order)` 공용 static 팩토리를 신설해 새 코드가
  쓰게 했다. 요금 스냅샷→응답 변환도 여러 서비스에 흩어져 있어 `FareBreakdownResponse.from(snapshot)`
  을 신설했다. **기존 중복의 통합은 이슈 범위 밖이라 손대지 않았다** — 새 코드가 복제를 늘리지 않고
  미래 통합의 앵커가 되게만 했다.
- **상세 조립을 DTO `from` 팩토리에**: 고객 `DeliveryDetailResponse.from` 과 같은 스타일로,
  주소 변환·proof null 처리·steps 파생을 팩토리에 두고 서비스는 조회·트랜잭션 경계만 책임진다.
- **통합·E2E 를 목록과 별도 파일로**: 목록 테스트는 스냅샷·정산 없이 완료 배송만 만들면 됐지만(목록이
  금액을 안 읽음), 상세는 실제 완료 흐름(`RiderDeliveryService.complete`)으로 FINAL 스냅샷·정산·proof
  를 만들어야 한다. 픽스처가 크게 달라 목록 테스트를 오염시키지 않으려 별도 클래스로 뒀다.

## 일부러 하지 않은 것

- **프론트 화면 연결**: 위 선택 1. 후속 미등록.
- **CANCELED 운행 기록 상세**: 상태 게이트가 COMPLETED 로 좁혀 취소 배송은 404 다. 라이더는 배차
  이후 취소가 MVP 범위 밖이라 배정된 CANCELED 배송 자체가 사실상 없다. 필요해지면 게이트를 넓힌다.
- **기존 steps/fare 변환 중복 3곳 통합**: 공용 팩토리를 신설하되 기존 호출부는 그대로 뒀다(범위 최소).
- **에러코드 체계**: 404 를 `message` 문자열로만 구분한다(#56 과 같은 미결). 상세는 사유별 UX 분기가
  필요 없어 그대로 뒀다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RiderDeliveryHistoryDetailResponseTest` | `from` 매핑(픽업/도착 뒤바뀜·정산액·운임·타임라인 순서), proof 없음 시 null 처리 |
| 통합 | `RiderDeliveryHistoryDetailServiceIntegrationTest` | 완료 흐름으로 만든 상세 전체 조립(운임=정산 맞물림·settledAt·타임라인·proof), 타인 것·없음·미완료 = 404 |
| E2E | `RiderDeliveryHistoryDetailE2ETest` | 로그인 라이더 200+상세 본문, 타인 것 404, 쿠키 없이 401(인터셉터 등록 회귀) |

실행 결과:

```text
./gradlew test --tests '*RiderDeliveryHistoryDetail*' \
  --tests '...RiderDeliveryHistoryDetailResponseTest'   → 9 tests, 0 failures (단위 2 + 통합 4 + E2E 3)
./gradlew test --tests '*RiderDeliveryHistory*' --tests '*OpenApiOperationId*' → BUILD SUCCESSFUL
```

### 검증하지 못한 것

- 단위 테스트의 타임라인은 순수 객체라 WAITING(`requestedAt`, @PrePersist)이 없어 ASSIGNED~COMPLETED
  만 검증한다. WAITING 포함 전체 타임라인은 통합 테스트가 실제 저장 후 확인한다.
- 프론트 화면 렌더는 이 이슈 범위 밖(백엔드만).

## 새로 생긴 미결 사항

- **상태 타임라인·요금 변환이 여전히 여러 곳에 복제돼 있다**: 이번에 공용 팩토리
  (`DeliveryStatusStepResponse.timelineOf`, `FareBreakdownResponse.from`)를 신설했지만 기존 3곳
  (`DeliveryTrackingQueryService.steps`, `DeliveryDetailResponse.steps`,
  `RiderDeliveryRequestService.toFareBreakdownResponse` 등)은 그대로다. 통합은 별도 리팩터 이슈 몫.
- **운행 기록 상세 프론트 연결(#217 계열)**: 백엔드는 준비됐고 `history/$deliveryId` 목업만 남았다.
- **금액 소관 분리 원칙의 예외**: "배송 기록=금액 없음, 정산=포인트 API"가 상세에서는 깨진다(정산 확인
  화면이라 의도된 예외). 목록/포인트 화면과의 금액 표기 일관성은 화면 작업 시 재확인 필요.
