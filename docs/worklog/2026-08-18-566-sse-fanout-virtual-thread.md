# SSE 팬아웃 실행기 고정 풀(4) → 가상 스레드 + Semaphore(1000) 전환 작업 기록

- 이슈: [#566](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/566)
- 브랜치: `feature/566-sse-fanout-virtual-thread`
- 범위: domain (Redis pub/sub 디스패치 실행기 내부 구조 변경, API 계약 변경 없음)
- 작성일: 2026-08-18
- 배경 문서: `docs/2026-08-17-01-study-sse-threadpool-and-slow-clients.md`,
  `docs/2026-08-17-02-test-results-sse-hol-blocking.md`,
  `docs/2026-08-17-03-discussion-virtual-thread-migration.md`

## 무엇을 만들었나

`RedisMessageListenerConfig.trackingEventExecutor`를 고정 풀(스레드 4 + 큐 1000, `ThreadPoolTaskExecutor`)에서
`Executors.newVirtualThreadPerTaskExecutor()` + `Semaphore(1000)` 조합으로 바꿨다. 메시지마다
별도 가상 스레드를 배정해 느린 SSE 클라이언트 하나가 다른 배송의 위치 전달을 막지 못하게 하고,
`Semaphore.tryAcquire()`(넌블로킹)로 동시 처리량 상한을 유지한다. 상한 초과 시 큐에 쌓지 않고
그 자리에서 드롭하며(`sse.fanout.dropped` 카운터), 현재 동시 처리량은 `sse.fanout.in_flight`
게이지로 노출한다.

### API

해당 없음 — 컨트롤러·DTO·응답 스키마 변화 없음. 내부 실행기 교체만이라 springdoc/OpenAPI 갱신 대상 아님.

### 화면

해당 없음.

### 스키마 변경

해당 없음.

## 사람이 고른 선택

### 1. 동시성 상한을 아예 없앨지, 고정 값으로 둘지

- **논의 배경**: 디스커션 문서에서 "가상 스레드는 원래 상한 없이 많이 만들어도 되는 설계"라는
  이유로 상한을 없애는 안(관측용 게이지만 두고 세마포어 제거)까지 검토했다.
- **선택지**:
  - (A) 상한 없음 — 가상 스레드 철학에 더 맞고, 나중에 `tryAcquire()`를 `acquire()`로 잘못
    "고칠" 위험 자체가 없음 / 단점: 극단적 플러드 시 이 앱의 고정 힙(512MB, #502)이 무제한
    가상 스레드 증가를 버티는지 실측 없이 배포하게 됨
  - (B) `Semaphore(1000)` 고정 — 검증 안 된 무제한 증가보다 근거는 약해도 상한이 있는 쪽이
    안전 / 단점: 1,000이라는 값 자체는 기존 큐 상한을 그대로 가져온 것이라 실측 근거는 아직 없음
- **고른 것**: (B), `Semaphore(1000)` 고정.
- **근거**: 사용자가 직접 "대신에 semaphore로 1000개 고정하고"로 지시.
- **영향**: `sse.fanout.dropped`가 늘어나는 시나리오가 생길 수 있다(느린 클라이언트가 많이
  몰리는 극단 상황) — 위치 데이터는 최신 값만 의미 있어(#297) 다음 갱신으로 자동 복구되므로
  허용 가능한 트레이드오프로 판단.

## 스스로 판단한 것

- **`tryAcquire()`(넌블로킹)만 쓴다, `acquire()`는 절대 안 쓴다**: `RedisMessageListenerContainer`가
  이 실행기를 자기 구독 스레드(Lettuce 이벤트루프)에서 직접 호출한다. 여기서 블로킹하면 Redis
  구독 처리 자체가 멈춘다 — 기존 고정 풀에서 큐가 꽉 찼을 때 실제로 관측된
  `TaskRejectedException`(Lettuce 콜백 안에서 발생, 테스트 결과 문서 참고)이 이 호출 경로를
  이미 증명해줬다.
- **드롭 시 예외를 던지지 않고 그냥 스킵**: 기존 `RedisMessageListenerContainer`의 기본
  동작(큐가 꽉 차면 `TaskRejectedException`을 던져 Lettuce 콜백 안에서 로그만 남고 그 메시지가
  사라짐)과 최종 결과(유실)는 같지만, 예외를 다시 던지지 않고 우리 쪽에서 명시적으로 카운터를
  올리는 편이 원인을 추적하기 쉽다.
- **메트릭 이름을 `sse.fanout.*`로 통일**: 기존에 스프링이 자동으로 주던
  `executor_active_threads`/`executor_queued_tasks`(테스트 때 실제로 포화를 확인하는 데 썼던
  지표)가 커스텀 `Executor`로 바꾸면서 사라지므로, 그 자리를 대신하는 커스텀 지표라는 걸
  이름으로 드러냈다.
- **상수 `MAX_CONCURRENT_DISPATCH`를 클래스 필드로 분리**: 매직넘버 1000을 그대로 박아넣지
  않고 이름 붙인 상수로 뺐다 — 나중에 실측 후 값을 조정할 때 한 곳만 고치면 된다.

## 일부러 하지 않은 것

- **채널(배송)별 coalescing**: "아직 처리 안 된 이전 이벤트가 있으면 새 걸로 덮어쓰기" 같은
  구조는 지금 트래픽 규모에서 근거 없는 선제 설계라 넣지 않았다. `sse.fanout.dropped`가
  실사용 중 자주 오른다는 게 확인되면 그때 근거를 갖고 도입한다(디스커션 문서에 명시).
- **HikariCP(DB 커넥션 풀) 관련 변경**: 이번 이슈와 무관하다는 게 실측으로 확인됐다
  (`docs/loadtest/2026-08-17-hikari-virtual-thread-pinning-evidence.md`) — 손대지 않았다.

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `RedisMessageListenerConfigTest` | 작업 제출 시 실제 실행됨, 상한(1000) 초과 시 호출 스레드가 블로킹되지 않고 즉시 드롭 처리되며 `sse.fanout.dropped`/`sse.fanout.in_flight`가 정확히 반영되는지 |
| E2E(기존) | `TrackingFanoutMultiInstanceE2ETest` | 새 실행기로 바꾼 뒤에도 2인스턴스 Redis pub/sub 팬아웃이 정상 동작하는지(회귀 확인) |

실행 결과:

```text
./gradlew test --tests 'com.turkey.quick.common.config.RedisMessageListenerConfigTest' \
               --tests 'com.turkey.quick.location.sse.TrackingFanoutMultiInstanceE2ETest'
→ 둘 다 BUILD SUCCESSFUL, 실패 없음

./gradlew test (전체 회귀) → BUILD SUCCESSFUL, 실패 없음
```

### 검증하지 못한 것

- **`slow-sse-client.py`를 새 구현에 대해 다시 돌리는 재현 실험**(디스커션 문서 검증 계획
  1번·4번) — 이번 작업에서는 코드·단위테스트·기존 E2E 회귀까지만 확인했고, 실제 부하테스트
  스택(로컬 docker network, 별도 시간 소요)으로 "무관한 고객 지연이 baseline을 유지하는지",
  "상한 소진 시에도 앱이 죽지 않는지"는 별도로 재현이 필요하다.
- **`1,000`이라는 permit 값의 실측 근거**: 정상 트래픽에서 동시 처리량이 어느 수준인지
  기준선을 재지 않았다 — 지금은 기존 큐 상한을 그대로 옮긴 값이다.

## 새로 생긴 미결 사항

- 위 "검증하지 못한 것"의 두 항목 — 배포 전 부하테스트로 확인 필요(디스커션 문서 「검증
  계획」에 남겨둠).
- `sse.fanout.dropped`에 대한 알림/대시보드 연동 여부는 별도 판단 필요.

## 후속: 재현 실험 결과 "검증하지 못한 것" 항목이 실패로 확인됨, 채널당 코얼레싱 추가

위에서 미룬 "`slow-sse-client.py` 재현 실험"을 실제로 돌려봤다. 결과: **"무관한 고객 지연이
baseline을 유지하는지"가 실패했다** — 위에서 "지금 트래픽 규모에서 근거 없는 선제 설계"라고
판단해 일부러 넣지 않았던 채널별 coalescing이, 실제로는 이 커밋 자체가 고치려던 문제를
그대로 재현시키는 원인이었다.

### 재현: 증상이 똑같이 돌아왔다

느린 클라이언트 5개 + 6채널 고빈도 트래픽으로 같은 조건을 재현하자, 느린 클라이언트와
전혀 무관한 6번째 고객(control)이 **60초 인시던트 내내 위치 이벤트를 한 건도 못 받았다** —
고정 플랫폼 스레드 풀(4) 시절과 피해 크기가 똑같았다.

원인: `Semaphore(1000)`가 배송(채널)을 구분하지 않는 **전역 예산**이었다. 정체된 채널
하나에 초당 ~400건씩 위치 갱신이 들어오면, 메시지마다 새 가상 스레드가 permit을 쥔 채
멈춘다(그 소켓이 안 읽는 상태라서). 정체 채널 5개가 초당 약 2,000개의 permit을 계속
재소모하니 **전역 상한(1000)이 0.5초 만에 소진**되고, 그 뒤로는 control을 포함한 어느
채널의 메시지든 `tryAcquire()`가 실패해 드롭됐다(60초 동안 313,678건). 상한을 올리는
것만으로는 해결 안 된다 — 정체 채널의 소모 속도에 비례해 소진 시간만 늘어날 뿐이다.

상세: `docs/loadtest/2026-08-18-sse-fanout-virtual-thread-verification.md`,
이슈 #566 코멘트.

### 추가로 고른 것: 배송(채널)당 동시 전송 1개로 제한

- **선택지**: (A) 세마포어 상한 자체를 대폭 키움 — 위에서 이미 반증됨(정체 채널 소모
  속도에 비례해 소진 시간만 늘어남, 힙 512MB 고정에서 상한을 무작정 키우는 것도 부담) /
  (B) 채널별 전용 워커 + "최신값 1개" 버퍼로 구조 자체를 바꿈 — 근본적이지만 워커 생명주기
  관리가 추가로 필요한 큰 변경 / (C) `TrackingSubscriber`에서 같은 배송의 이전 전송이 아직
  안 끝났으면 새 메시지를 즉시 버림(permit을 짧게 쓰고 반환) — 정체 채널이 몇 개든 각자
  permit 1개만 오래 붙잡게 만듦.
- **고른 것**: (C). 기존에 이미 받아들인 "위치 데이터는 최신 값만 의미 있다"(#297/#391)
  철학과 정확히 일치하고, 변경 범위가 `TrackingSubscriber.onMessage()` 한 곳으로 가장 작다.
- **근거**: (A)는 실측으로 이미 기각됐고, (B)는 이 문제를 고치는 데 필요한 것보다 큰
  구조 변경이라 지금 단계에서 넣을 이유가 없다고 판단.
- **관측**: 신설 카운터 `sse.fanout.coalesced`(같은 배송의 이전 전송이 안 끝나 버려진
  이벤트) — 기존 `sse.fanout.dropped`(전역 예산 소진으로 인한 진짜 손실)와 의미를
  분리했다. 정상 동작이라면 `dropped`는 0에 가깝고 `coalesced`만 오른다.

### 재검증

같은 조건으로 다시 돌린 결과, control이 60초 인시던트 내내 끊기지 않고 3초당
2,200~2,950건을 1~12ms 지연으로 계속 수신했다. `sse_fanout_in_flight`는 5~7(정체 채널
수와 일치)에 머물렀고, `sse_fanout_dropped_total`은 인시던트 60초 동안 0건이었다. 상세:
`docs/loadtest/2026-08-18-sse-fanout-per-channel-cap-fix-verification.md`.

### 테스트(추가)

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `TrackingSubscriberTest` | 같은 배송의 이전 전송이 아직 안 끝났으면 새 메시지가 `publish()`를 또 부르지 않고 즉시 버려지는지(`sse.fanout.coalesced` 증가) |
| E2E(기존, 회귀) | `TrackingFanoutMultiInstanceE2ETest` | 채널별 게이트 추가 후에도 2인스턴스 팬아웃이 정상 동작하는지 |
| 재현(수동) | `backend/loadtest/local/slow-sse-client.py` | 무관한 고객이 인시던트 중에도 baseline 수준 수신을 유지하는지(위 "재검증" 결과) |

## 갱신된 미결 사항

- 세마포어 상한(1000)의 의미가 "동시 처리 메시지 수"에서 "동시에 정체 중인 서로 다른
  배송 채널 수"로 바뀌었다. 정상 범위에서는 한 자릿수만 쓰이는 게 확인됐으나, 값 자체를
  이 새 기준으로 재산정할지는 아직 판단하지 않았다.
- `sse.fanout.dropped`/`sse.fanout.coalesced`에 대한 알림·대시보드 연동 여부는 여전히
  별도 판단 필요.

## 후속 2: deliveryId 단위 게이트가 놓친 것 — 같은 배송의 다른 연결(멀티탭)까지 같이 굶김

바로 위 수정(`TrackingSubscriber`에서 deliveryId 단위로 동시 전송 1개 제한)을 사람에게
설명하던 중, `SseRegistry`가 배송 하나당 **연결을 여러 개**(`Set<SseEmitter>`) 들고 있을 수
있다는 사실을 놓쳤다는 게 드러났다 — 같은 고객이 같은 배송 추적 화면을 탭 2개로 열면 이런
상태가 된다. deliveryId 단위로 막으면, 그 배송의 느린 탭 하나 때문에 **같은 배송의 멀쩡한
다른 탭까지** 같이 굶는다(코드에는 있었지만 실측·재현 없이 발견된 설계 결함).

### 고른 것: 게이트를 `TrackingSubscriber`(deliveryId 키)에서 `SseRelay`(개별 `SseEmitter`
키)로 옮김

- **선택지**: (A) `TrackingSubscriber`에 그대로 두고 키를 `(deliveryId, emitter)` 쌍으로
  바꿈 / (B) 게이트 자체를 `SseRelay.publish()`로 옮기고 emitter 객체를 키로 씀.
- **고른 것**: (B). `SseRelay`가 실제로 emitter 하나하나를 순회하며 보내는 곳이라 "이 연결에
  지금 보내는 중인가"를 알기에 가장 자연스러운 위치였고, `TrackingSubscriber`는 채널 파싱
  책임만 남아 원래 형태로 돌아갔다.
- **효과**: 단일 탭(배송당 연결 1개) 시나리오에서는 이전과 동일하게 동작하므로(가장 흔한
  경우라 회귀 없음), 멀티탭 케이스만 추가로 고쳐진다.

### 재검증

같은 조건(느린 클라이언트 5개, 6채널 트래픽)으로 다시 돌려 회귀가 없는지 확인했다 — control이
여전히 60초 인시던트 내내 baseline 수준(3초당 2,200~2,760건, 1~14ms)을 유지했고,
`sse_fanout_dropped_total`은 0이었다. 상세:
`docs/loadtest/2026-08-18-sse-fanout-per-emitter-cap-fix-verification.md`.

### 테스트(추가)

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `SseRelayTest.coalescesMessagesForSameInFlightEmitter` | 같은 emitter의 이전 전송이 안 끝났으면 새 메시지가 버려지는지(이전 회귀 테스트를 emitter 단위로 옮김) |
| 단위(신규) | `SseRelayTest.doesNotStarveOtherEmitterOfSameDeliveryWhileOneIsInFlight` | 같은 배송의 다른 연결이 하나가 정체돼도 굶지 않는지 — deliveryId 단위 게이트였다면 실패했을 테스트 |
| E2E(기존, 회귀) | `TrackingFanoutMultiInstanceE2ETest` | 게이트 위치를 옮긴 후에도 2인스턴스 팬아웃 정상 동작 |
| 재현(수동) | `backend/loadtest/local/slow-sse-client.py` | 단일 탭 시나리오에서 여전히 baseline 유지(회귀 없음) |
