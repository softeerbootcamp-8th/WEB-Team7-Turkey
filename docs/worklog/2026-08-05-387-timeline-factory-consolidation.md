# 상태 타임라인 파생 중복 3곳 통합 작업 기록

- 이슈: [#387](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/387)
- 브랜치: `refactor/387-timeline-factory-consolidation` (**`feature/71` 위 스택** — `timelineOf` 의존)
- 범위: backend (리팩터)
- 작성일: 2026-08-05

## 무엇을 만들었나

배송 상태 타임라인(`delivery_order` 시각 컬럼 → `List<DeliveryStatusStepResponse>`) 파생 로직이 3곳에
복붙돼 있던 것을, #71이 신설한 공용 팩토리 `DeliveryStatusStepResponse.timelineOf(order)` 호출로 통합했다.

- `order/dto/DeliveryDetailResponse` — private `steps`/`addStep` 제거, `timelineOf` 호출. `ArrayList` import 정리.
- `order/service/DeliveryTrackingQueryService` — package-private `steps`·`step` 제거, `timelineOf` 호출.
  더는 안 쓰는 import 정리(`OrderStatus`·`LocalDateTime`·`Objects`·`Stream`·`List`).
- `rider/dto/RiderDeliveryResponse` — private `steps`/`addStep` 제거, `timelineOf` 호출.

### API / 화면 / 스키마

해당 없음. 순수 내부 리팩터로 응답 계약(필드·형식)은 그대로다.

## 사람이 고른 선택

- **이 통합을 별도 이슈로 수행**(사람 지시): #71에서 "기존 복제 3곳 통합은 범위 밖"으로 남긴 것을,
  사람이 별도 이슈(#387)로 만들어 구현하라고 지시했다.

## 스스로 판단한 것

- **동작 동치 확인 후 교체**: 세 복제가 `timelineOf`와 같은 결과를 내는지 먼저 확인했다.
  `DeliveryDetailResponse.steps`·`DeliveryTrackingQueryService.steps`는 상태 집합(WAITING~CANCELED)·
  순서·null 필터·불변 반환이 완전히 동일(구현만 ArrayList vs Stream)했다.
- **`RiderDeliveryResponse.steps`의 CANCELED 누락은 무해한 통일로 처리**: 이 한 곳만 CANCELED 단계를
  담지 않았다. 하지만 라이더 진행 배송은 배차 후 취소가 MVP 범위 밖이라 `canceledAt`이 항상 null →
  실제 출력은 동일하다. `timelineOf`로 통일하면 취소 주문에서 CANCELED가 나타나지만(더 정확) 그 경로는
  발생하지 않는다. 그래서 안전한 교체로 판단했다.
- **호출부·테스트 조사**: 세 `steps` 메서드는 전부 private/package-private이고 외부·테스트에서 직접
  부르는 곳이 없어(문서 주석 언급만) 교체가 안전했다.

## 일부러 하지 않은 것

- **요금 스냅샷 → `FareBreakdownResponse` 변환 중복 통합**: `FareBreakdownResponse.from(snapshot)`도 #71에서
  신설했지만, 기존 변환처 중 일부(`DeliveryService`)는 스냅샷이 아니라 계산값에서 직접 조립해 1:1 치환이
  안 된다. 이 이슈(타임라인 3곳)와 성격이 달라 범위에서 제외했다 — 필요 시 별도 이슈. 후속: 미등록.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| — | (신규 없음) | 순수 리팩터라 기존 테스트로 회귀만 확인 |

실행 결과:

```text
./gradlew test --tests '*DeliveryDetail*' --tests '*Tracking*' \
  --tests '*RiderDelivery*' --tests '*DeliveryStatusStep*' → BUILD SUCCESSFUL
./gradlew test (전체)                                       → BUILD SUCCESSFUL
```

기존 테스트가 세 화면의 타임라인 출력을 고정하고 있어, 전체 통과가 곧 동작 보존의 근거다.

## 새로 생긴 미결 사항

- 요금 변환(`FareBreakdownResponse.from`) 중복 통합은 위 「일부러 하지 않은 것」대로 남겨 둠 — 스냅샷
  기반이 아닌 조립처가 있어 별도 설계가 필요하다.
