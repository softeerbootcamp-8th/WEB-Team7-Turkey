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
