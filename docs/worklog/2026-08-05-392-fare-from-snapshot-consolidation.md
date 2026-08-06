# 요금 스냅샷 변환 중복 통합 작업 기록

- 이슈: [#392](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/392)
- 브랜치: `refactor/392-fare-from-snapshot-consolidation` (dev 기준)
- 범위: backend (리팩터)
- 작성일: 2026-08-05

## 무엇을 만들었나

`OrderFareSnapshot` → `FareBreakdownResponse` 변환이 여러 곳에 복붙돼 있던 것 중, **스냅샷을 그대로 옮기는
3곳**을 #71이 신설한 공용 팩토리 `FareBreakdownResponse.from(snapshot)` 호출로 통합했다. #387(타임라인
통합)의 쌍둥이 작업이다.

- `order/service/DeliveryDetailQueryService.fare` — 스냅샷 조회 후 `from(snapshot)`.
- `rider/service/RiderDeliveryRequestService` — private `toFareBreakdownResponse(snapshot)` 삭제, 호출부에서 직접 `from(estimate)`.
- `order/service/DeliveryService.toResponse(order)` — 멱등 재전송 응답, ESTIMATE 스냅샷 되읽어 `from(snapshot)`.

### API / 화면 / 스키마

해당 없음. 순수 내부 리팩터로 응답 계약 불변.

## 사람이 고른 선택

- **이 통합을 별도 이슈·브랜치로 수행**(사람 지시): #387에서 "요금 변환은 범위 밖"으로 남긴 것을,
  "왜 요금은 안 하냐"는 지적에 따라 별도 이슈(#392)로 분리해 구현. #387은 이미 dev 머지됨.

## 스스로 판단한 것

- **4곳 중 3곳만 통합, 1곳은 제외**: `new FareBreakdownResponse(...)` 4곳을 확인한 결과 —
  - A. `DeliveryDetailQueryService.fare`, B. `RiderDeliveryRequestService.toFareBreakdownResponse`,
    C. `DeliveryService.toResponse(order)` 는 **스냅샷의 필드를 그대로 옮기는** 형태라 `from(snapshot)`으로
    1:1 치환된다.
  - D. `DeliveryService.calculateFare(policy, itemType, distanceMeters)` 는 **스냅샷이 아니라 FarePolicy
    계산값**에서 조립한다(요금을 *계산*해 이후 스냅샷으로 저장하는 원천). 넘길 스냅샷 자체가 없어
    `from`으로 치환 불가라 그대로 뒀다.
- **B는 메서드 삭제까지**: `toFareBreakdownResponse`는 스냅샷을 그대로 옮기기만 해 공용 팩토리와 완전히
  겹친다. 호출부가 한 곳뿐이라 메서드를 지우고 `from`을 직접 부르는 편이 명확하다.

## 일부러 하지 않은 것

- **`calculateFare`(D) 통합**: 위 사유(스냅샷 아님)로 제외. 요금 계산 로직 자체를 팩토리로 옮기는 것은
  성격이 다른 설계라 범위 밖.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| — | (신규 없음) | 순수 리팩터라 기존 테스트로 회귀만 확인 |

실행 결과:

```text
./gradlew test --tests '*DeliveryDetail*' --tests '*DeliveryService*' \
  --tests '*RiderDeliveryRequest*' --tests '*FareBreakdown*' --tests '*Quote*' → BUILD SUCCESSFUL
./gradlew test (전체)                                                          → BUILD SUCCESSFUL
```

기존 테스트가 세 소비처의 요금 응답을 고정하고 있어, 전체 통과가 곧 동작 보존의 근거다.

## 새로 생긴 미결 사항

- 없음. 요금·타임라인 두 공용 팩토리(#71)로의 중복 통합은 이 이슈(#392)와 #387로 마무리됐다
  (계산 원천 `calculateFare` 제외).
