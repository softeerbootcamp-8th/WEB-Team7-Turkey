# 위치 갱신 50명 동시 — 직렬화 시간 · 발행→SSE emitter 총 소요시간

## 목적

`POST /api/rider/location` 실사용자 50명이 동시에 위치를 보내는 상황을 앱 인스턴스 2대로 재현하고,
다음 두 구간을 실측한다(#391 「BUSY 위치 전송 서버 요청량 영향 미확인」에 대한 답).

1. `TrackingPublisher.publish` 안에서 `objectMapper.writeValueAsString` 이 걸리는 시간
2. 라이더 위치 전송(발행) 시점부터 `SseRelay` 가 `emitter.send()` 를 완료하는 시점까지

## 계측 방법 (임시, 코드 주석에 `#391` 로 표시)

- `backend/src/main/java/com/turkey/quick/location/sse/TrackingPublisher.java`
  - 직렬화 구간을 Micrometer `Timer("tracking.publish.serialize")` 로 감쌌다(`publishPercentileHistogram()`).
  - 위치 발행 직전 시각을 Redis 키 `tracking:debug:t0:{deliveryId}`(TTL 5초)에 SET.
    **인스턴스가 2대라 JVM 메모리(ConcurrentHashMap 등)로는 못 넘긴다** — 발행과 구독이 서로 다른
    인스턴스에서 일어날 수 있어 공유 저장소(Redis)가 필요하다.
- `backend/src/main/java/com/turkey/quick/location/sse/TrackingSubscriber.java`
  - `onMessage` 에서 위 키를 GETDEL 로 읽고, `sseRelay.publish(...)` 호출이 끝난 뒤 경과를
    Micrometer `Timer("tracking.publish.e2e")` 에 기록.
- 두 지표 모두 `/actuator/prometheus` 로 노출되며, 로컬 Prometheus(`localhost:9099`)에
  `histogram_quantile` 로 직접 질의했다(collect.py 기본 RAW 목록에는 없는 커스텀 지표라 수동 조회).

## 인프라 변경 (측정을 위해 임시로 조정)

- `backend/docker-compose.yml` — app 서비스의 actuator 포트 고정 매핑(`8081:8081`)을
  `8081-8090:8081` 범위로 열었다. `--scale app=2` 는 고정 매핑과 충돌해서 필요했다.
  **부하테스트 끝난 뒤 `8081:8081` 로 되돌릴 것** — bootRun 과 포트 충돌을 막던 안전장치라
  계속 열어두면 안 된다.
- `infra/monitoring-ec2/targets.local/spring-app.yml` — 인스턴스 2대(`host.docker.internal:8082`,
  `:8081`)를 모두 스크레이프하도록 타겟 추가. Prometheus 는 `force-recreate` 로 반영해야 했다
  (브랜치 전환 후 bind mount 가 옛 inode 를 붙잡는 알려진 함정, SKILL.md 참고).
- 시드: `scripts/seed-loadtest-riders.sql` 기본값(`@n=100`)으로 `lt_r1..100`(BUSY) / `lt_c1..100` 생성,
  그중 `lt_r1..50` / `lt_c1..50` 사용.

## 1차 런 — SSE 구독자 없음 (초록불 거짓말 발견)

`testid=location-concurrent50-20260812-181531`, 50 VU, 170초 구간(유지 구간 120초).

| 항목 | 값 |
|---|---|
| 총 요청 | 730,126건 |
| 평균 처리량 | 4,295 req/s (유지 구간 5,014 req/s) |
| k6 http_req_duration | avg 9.16ms, p95 24.07ms, p99 31.71ms, max 215ms |
| 실패율 | 0.00% |
| 앱 CPU (유지 구간) | 154% (2코어 제한 중) |
| MySQL CPU | 203% (거의 만석) |
| Redis CPU | 21% |

**1차 런의 문제**: k6 시나리오(`rider-location-update.js`)는 위치 갱신 POST 만 치고 SSE 를 구독하는
클라이언트가 없다. `SseRegistry.connectionOf(deliveryId)` 가 항상 빈 컬렉션이라
`SseRelay.publish()` 의 `emitter.send()` 가 **한 번도 실행되지 않았다.** 이 런의
`tracking.publish.e2e` 는 "발행 → Redis Pub/Sub 왕복 → 구독자 처리(GETDEL)" 까지이지,
요청한 "emitter 가 send 하는 데까지" 가 아니다.

| 지표 | p50 | p95 | p99 | max | mean |
|---|---|---|---|---|---|
| `tracking.publish.serialize` | 0.50ms | 0.95ms | 0.99ms | 6.78ms | **2.97µs** |
| `tracking.publish.e2e` (emitter 미실행) | 0.93ms | 2.60ms | 5.05ms | 179ms | 1.156ms |

## 2차 런 — 라이더 수만큼 SSE 구독자 붙여서 재측정

`lt_c1..50` 고객으로 로그인해 각자 자기 배송의 `/api/customer/deliveries/{id}/tracking/stream`
을 백그라운드 `curl -N --no-buffer` 로 미리 열어 두고(50개 동시 연결, `:connected` 확인 후 실제
위치 데이터 수신까지 확인), 동일 조건으로 위치 갱신 부하를 다시 걸었다.

`testid=location-sse-e2e-20260812-182913`, 50 VU, 150초 구간(유지 구간 120초).

| 항목 | 값 |
|---|---|
| 총 요청 | 643,227건 |
| 평균 처리량 | 4,288 req/s (유지 구간 4,495 req/s) |
| k6 http_req_duration | avg 10.25ms, p95 21.92ms, p99 29.21ms, max 142ms |
| 실패율 | 0.00% |
| Tomcat 현재 연결 최대 | 83 (1차 런 36~41 대비 +약 42 — SSE 연결 50개가 열려 있던 만큼 증가, 정합) |

| 지표 | p50 | p95 | p99 | max | mean |
|---|---|---|---|---|---|
| `tracking.publish.serialize` | 0.50ms | 0.95ms | 0.99ms | 6.63ms | 3.87µs |
| `tracking.publish.e2e` (emitter 실행됨) | **1.27ms** | **3.38ms** | **5.78ms** | 127ms | **1.511ms** |

## 결론

- **Jackson 직렬화(`writeValueAsString`)는 극히 짧다.** 히스토그램 버킷이 1ms 부터 시작해
  p50/p95/p99 는 버킷 경계값으로 뭉개지지만(사실상 분해능 밖), `sum/count` 로 계산한 평균은
  **약 3~4마이크로초**(120만~185만 건 기준)다 — `LocationPayload` 같은 작은 DTO는 JIT 워밍업
  후 직렬화 자체가 병목이 아니다. 1차 실측(단발 호출, 워밍업 전) 은 각각 3.46ms(콜드) /
  0.998ms(두 번째 호출)이었는데, 이번 지속 부하에서는 3~4µs 로 3자릿수 차이가 났다 —
  단발 로그 측정이 JIT 최적화 이전 값이라 실사용(지속 부하) 수치와 크게 다를 수 있다는 근거.
- **`emitter.send()` 를 실제로 태우면 e2e 시간이 늘어난다.** SSE 구독자가 없을 때(p95 2.60ms) 대비
  50개 실제 연결에 보낼 때(p95 3.38ms) **+0.78ms**, p99 는 +0.73ms, 평균은 +0.355ms 늘었다.
  `emitter.send()` 자체는 가벼운 버퍼 쓰기지만 0은 아니고, 열린 커넥션 수(Tomcat 연결 83개)에
  비례해 약간의 오버헤드가 실측으로 확인됐다.
  → **"발행 → SSE emitter 전송 완료" 총 소요시간은 p50 1.27ms / p95 3.38ms / p99 5.78ms
  (50 VU, 앱 인스턴스 2대, 실제 SSE 구독자 50명 기준)**.
- **위치 갱신 자체는 50 VU·2인스턴스에서 여유 있게 처리됐다.** k6 p95 21.9~24.1ms, 실패 0%,
  MySQL CPU 가 154~203%(2코어 제한 기준 만석에 가까움)로 **DB 가 먼저 한계에 닿는 그림**이고
  앱·Redis 는 여유가 있었다(1차 런 앱 CPU 125~154%, Redis 17~21%). 이 세션에서 재세팅한 지표만
  보면 위치 전송 경로(직렬화+발행+구독+emitter) 자체는 병목이 아니고, 왕복 수(요청당 MySQL
  문장 13~14회)를 줄이는 쪽이 더 유효한 다음 단계로 보인다.

## 한계 · 참고

- 컨테이너 CPU/메모리(`docker stats`) 샘플러가 2차 런 구간을 못 덮었다(15분 기본 실행시간이
  1차 런 직후 만료). 1차 런의 컨테이너 CPU 배분만 리포트에 있고, 2차 런은 Prometheus 쪽
  JVM/앱 지표(GC, 힙, Tomcat 연결)만 있다.
- 두 커스텀 지표 다 `publishPercentileHistogram()` 기본 버킷을 썼다. 서브밀리초 정밀도가
  필요하면 `minimumExpectedValue` 를 낮춰 버킷을 재설정해야 한다(직렬화 쪽만 해당, e2e 는
  ms 단위라 기본 버킷으로 충분).
- raw 시계열: `docs/loadtest/2026-08-12-location-concurrent50-20260812-181531-raw.json`,
  `docs/loadtest/2026-08-12-location-sse-e2e-20260812-182913-raw.json`

## 정리 필요 (사람 판단 대기)

이 두 지표(`tracking.publish.serialize`, `tracking.publish.e2e`)와 관련 Redis 키(`tracking:debug:t0:*`)는
**임시 계측**이다. 유지할지, 되돌릴지 판단이 필요하다:
- 유지한다면: 코드 주석의 "부하테스트 임시 계측(#391)" 표현을 정식 지표 설명으로 바꾸고,
  Grafana 대시보드에 패널을 추가하는 편이 낫다.
- 되돌린다면: `TrackingPublisher`/`TrackingSubscriber` 의 Timer·Redis T0 코드를 제거.
- 인프라 쪽은 **되돌리는 게 기본**이다: `backend/docker-compose.yml` 의 actuator 포트를
  `8081:8081` 로, `infra/monitoring-ec2/targets.local/spring-app.yml` 을 단일 타겟으로.
