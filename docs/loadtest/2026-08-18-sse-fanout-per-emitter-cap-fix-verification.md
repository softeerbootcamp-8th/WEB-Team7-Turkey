# SSE 팬아웃 연결(SseEmitter)당 동시 전송 1개 제한 — 멀티탭 격리 수정 검증

## 배경

[`2026-08-18-sse-fanout-per-channel-cap-fix-verification.md`](./2026-08-18-sse-fanout-per-channel-cap-fix-verification.md)
에서 `TrackingSubscriber`에 배송(deliveryId) 단위로 동시 전송 1개 제한을 넣어 HOL blocking을
고쳤다. 그런데 `SseRegistry`가 배송 하나에 `Set<SseEmitter>`(연결 여러 개)를 들고 있을 수
있다는 걸 놓쳤다 — 같은 고객이 같은 배송 추적 화면을 탭 2개로 열면 이런 상태가 된다.
deliveryId 단위로 막으면, 그 배송의 느린 탭 하나 때문에 **같은 배송의 멀쩡한 다른 탭까지**
같이 굶는다. 이 PR에서 게이트를 `TrackingSubscriber`(deliveryId 키)에서 `SseRelay`(개별
`SseEmitter` 키)로 옮겨 이 문제를 없앴다.

## 변경 내용

- `TrackingSubscriber`는 채널 파싱 후 `SseRelay.publish()`를 호출하는 원래 형태로 되돌렸다.
- `SseRelay.publish()`가 배송에 등록된 emitter를 순회하며, **개별 emitter 단위**로
  `ConcurrentHashMap.newKeySet()` 게이트를 적용한다. 같은 emitter에 대한 이전 전송이 아직 안
  끝났으면 그 emitter만 건너뛰고(`sse.fanout.coalesced` 증가), 같은 배송의 다른 emitter는
  그 사이에도 정상 전송된다.

## 단위 테스트로 확인한 것

`SseRelayTest`에 두 개 추가:

1. `coalescesMessagesForSameInFlightEmitter` — 같은 emitter로 온 메시지가, 이전 전송이 안
   끝났으면 `send()`를 또 안 부르고 버려지는지(회귀 방지, 이전과 동일한 보장).
2. `doesNotStarveOtherEmitterOfSameDeliveryWhileOneIsInFlight` — 같은 배송에 연결이 2개
   (`slow`, `healthy`)일 때, `slow`가 막혀 있는 동안 온 새 메시지도 `healthy`에는 정상
   전달되는지. **deliveryId 단위로 막았던 이전 구현이었다면 이 테스트가 실패한다** — `healthy`도
   같이 코얼레싱돼 버렸을 것이다.

`TrackingSubscriberTest`는 다시 원래 3개 케이스로 돌아갔다(게이트 로직이 `SseRelay`로
옮겨갔으므로).

## 부하테스트로 재확인 — 기존 시나리오(단일 탭) 회귀 없음

게이트 위치를 옮긴 것이 기존에 고친 문제(배송 하나가 정체돼도 무관한 다른 배송은 안 굶는 것)를
다시 깨지 않았는지, 같은 조건으로 재확인했다: `rider-location-update.js`(`RIDER_COUNT=6
MAX_VU=6 STEPS=1 RAMP_SECONDS=5 HOLD_SECONDS=180`) + `slow-sse-client.py`(기본값).

| 지표 | 결과 |
|---|---|
| control(무관한 배송) 60초 인시던트 중 수신 | 3초당 2,200~2,760건, 지연 1~14ms — baseline과 동일 수준 유지 |
| `sse_fanout_dropped_total`(전역 예산 소진) | 0 |
| `sse_fanout_coalesced_total` | 260,788(같은 emitter의 초과분, 정상 동작) |
| `sse_fanout_in_flight` | 낮음(수 자릿수, 상한 1000과 거리 멂) |
| 앱 헬스체크·예외·OOM | 정상, 0건 |

단일 탭(배송당 연결 1개) 시나리오에서는 deliveryId 단위 게이트와 emitter 단위 게이트가
동일하게 동작하므로 결과가 이전 검증과 일치한다 — 이번 변경은 멀티탭 케이스만 추가로 고치고
기존 보장은 그대로 유지한다.

## 원본 데이터

- `2026-08-18-sse-vt-fixB-20260818-225351-raw.json`
- 앱 로그 발췌(세션 임시 파일): `/tmp/app-capture-sse-vt-fixB.log`
