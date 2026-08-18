# 디스커션: SSE 팬아웃 스레드풀을 가상 스레드로 — 해결책과 동작 방식

- 배경 개념: `docs/2026-08-17-01-study-sse-threadpool-and-slow-clients.md`
- 실측 근거: `docs/2026-08-17-02-test-results-sse-hol-blocking.md`
- 구현 이슈: [#566](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/566)
- 작업 기록: `docs/worklog/2026-08-18-566-sse-fanout-virtual-thread.md`

## 문제 요약 (1줄)

느린 SSE 클라이언트 하나가 무관한 배송 4건 이상의 위치 전달을 최대 60초 동안 막고, 그 60초
동안 도착한 다른 모든 배송의 위치 갱신을 즉시 유실시킨다(실측: 73,956건/60초). 원인은
`RedisMessageListenerConfig.trackingEventExecutor`가 스레드 4개로 고정된 풀이라, 이 서비스의
모든 배송이 이 4개를 공유하기 때문이다.

## 왜 지금 다시 여는가

앱 전체는 이미 `spring.threads.virtual.enabled=true`로 가상 스레드로 전환돼 있다. 하지만
`trackingEventExecutor`는 명시적으로 만든 빈이라 이 전환에서 빠져 있다 — 가상 스레드로
바꾸면 "스레드 4개"라는 상한 자체가 없어져 위 문제가 구조적으로 해소된다.

## 결정: 가상 스레드 + `Semaphore(1000)` 고정 (사람 확인, 2026-08-18, #566)

디스커션 중 "상한을 아예 없애면 어떤가"까지 검토했으나(아래 「상한을 없애는 안을 검토했던
이유」 참고), 최종적으로는 **동시성 상한을 `Semaphore(1000)`으로 고정**하는 쪽으로 사람이
직접 결정했다.

```java
@Bean
public Executor trackingEventExecutor(MeterRegistry registry) {
    ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    Semaphore limiter = new Semaphore(MAX_CONCURRENT_DISPATCH);   // 1_000
    Counter dropped = Counter.builder("sse.fanout.dropped")
            .description("동시 디스패치 상한 초과로 유실된 팬아웃 이벤트(at-most-once)")
            .register(registry);
    Gauge.builder("sse.fanout.in_flight", limiter,
                    l -> MAX_CONCURRENT_DISPATCH - l.availablePermits())
            .description("현재 동시에 처리 중인 팬아웃 디스패치 수")
            .register(registry);

    return command -> {
        if (!limiter.tryAcquire()) {   // acquire()로 바꾸면 Redis 구독 스레드 자체가 멈춘다
            dropped.increment();
            log.warn("event=SSE_FANOUT_BACKPRESSURE_DROP");
            return;
        }
        virtualThreads.execute(() -> {
            try { command.run(); } finally { limiter.release(); }
        });
    };
}
```

구현은 `RedisMessageListenerConfig.trackingEventExecutor()`에 있다.

## 이게 왜 문제를 해결하는가

- **격리**: 메시지마다 별도의 가상 스레드가 배정된다. 느린 연결 하나가 붙잡는 건 그 하나의
  가상 스레드뿐이고, 다른 배송의 디스패치는 전혀 영향받지 않는다 — "스레드 4개를 나눠
  쓴다"는 공유 자체가 없어진다.
- **호출자(Netty/Lettuce 이벤트루프)는 블로킹되지 않는다**: `Semaphore.tryAcquire()`(인자
  없는 버전)는 정의상 절대 파킹하지 않는다 — 상한이 다 찼어도 즉시 `false`를 반환한다.
  `virtualThreads.execute()`도 가상 스레드를 만들어 작업을 넘기고 곧바로 리턴한다.
  `RedisMessageListenerContainer`가 자기 내부 구독 스레드에서 이 실행기를 직접 호출하므로,
  여기서 조금이라도 블로킹하면 Redis 구독 처리 자체가 멈춘다(테스트 결과 문서의
  `TaskRejectedException` 스택트레이스가 정확히 이 호출 경로를 보여준다) — 그래서
  `acquire()`가 아니라 `tryAcquire()`여야 한다.
- **드롭 정책은 "가장 최근 것 버리기"로 충분하다**: 상한을 넘으면 큐에 쌓지 않고 그 자리에서
  스킵한다. 위치 데이터는 최신 값만 의미 있고(#297), 다음 위치 갱신이 곧 다시 오므로(BUSY
  라이더는 최소 0.5초~120초마다 재전송, #391) 자동으로 복구된다.
- **관측을 반드시 같이 추가한다**: 기존 `ThreadPoolTaskExecutor`가 스프링 자동 계측으로 주던
  `executor_active_threads`/`executor_queued_tasks`(테스트에서 실제로 이 지표로 포화를
  확인했다)가 커스텀 `Executor`로 바꾸면서 사라진다. `sse.fanout.dropped`/`sse.fanout.in_flight`가
  그 자리를 대신한다.

## 상한을 없애는 안을 검토했던 이유 (참고, 최종 결정 아님)

가상 스레드는 원래 많이 만들어도 되는 설계라 상한 자체를 없애는 안도 진지하게 검토했다.
장점은 "몇 개가 적정한가"라는, 가상 스레드 전환으로 애초에 없애려던 종류의 질문(플랫폼
스레드 풀 크기를 정하던 그 고민)이 되살아나지 않는다는 것이었다. 그러나:

- 이 앱은 힙이 512MB로 고정돼 있고(#502) 단일 인스턴스다. 상한이 없으면 극단적인
  플러드(우리가 실측한 1,500 req/s급)가 들어왔을 때 60초(Tomcat 쓰기 타임아웃) 동안
  수만~수십만 개의 가상 스레드가 동시에 파킹된 채 쌓일 수 있는데, 그 규모에서 이 힙이
  버티는지는 실측한 적이 없어 리스크가 검증되지 않은 상태였다.
- 그래서 **검증되지 않은 무제한 증가보다, 근거 있는 고정 상한(1,000)을 두고 필요하면 나중에
  올리는 쪽이 안전하다**는 판단으로 `Semaphore(1000)`으로 최종 결정했다. 1,000은 기존
  큐 상한과 같은 값을 그대로 가져온 것이라 근거가 강한 숫자는 아니다 — 아래 검증 계획에서
  실측 후 조정 여부를 판단한다.

## 의도적으로 하지 않는 것

배송별로 "아직 처리 안 된 이전 이벤트가 있으면 새 걸로 덮어쓰기" 같은 채널 단위 coalescing은
지금 하지 않는다. 채널별 상태를 미리 추적하는 구조는 지금 트래픽 규모에서 근거 없는 선제
설계다. `sse.fanout.dropped`가 실사용 중 자주 오른다는 게 확인되면, coalescing이든 상한 상향이든
근거를 갖고 도입한다.

## HikariCP는 별도로 건드리지 않는다

`docs/2026-08-17-02-test-results-sse-hol-blocking.md`의 결과 4에서 확인했듯, HikariCP 풀
크기(10)는 인위적 제한이 아니라 실제 DB 커넥션 개수 제한이라 이번 변경과 무관하다. 가상
스레드 전환 시 우려되는 "핀닝" 문제도 현재 드라이버·부하 조건에서는 실측으로 확인되지
않았다. 단, 이건 "지금은 안전하다"는 확인이지 "영원히 안전하다"는 보장이 아니다 — 드라이버
버전 변경이나 느린 쿼리 추가 시 같은 방법(`tracePinnedThreads`)으로 재검증이 필요하다는
점을 확인 필요 항목으로 남긴다.

## 검증 계획 (배포 전 필수)

1. **회귀 확인**: `slow-sse-client.py --slow 5` 시나리오를 새 구현에 다시 돌려서, 무관한
   고객의 지연이 baseline 수준(0ms대)을 유지하는지 — 이게 "HOL blocking이 실제로 해소됐다"는
   합격 기준이다.
2. **평상시 `sse.fanout.dropped`가 0인지, `sse.fanout.in_flight`가 어느 수준인지 확인**:
   정상 트래픽에서 동시 처리량 기준선을 잡아야 1,000이라는 상한이 여유 있는 값인지 판단할
   수 있다.
3. **상한(1,000)이 실제로 안전판 역할을 하는지 확인**: 느린 클라이언트 수천 개 + 고빈도
   위치 전송으로 permit을 다 소진시켜, `sse.fanout.dropped`가 늘어나면서도 **앱이 죽지 않고
   상한과 무관한 다른 배송은 계속 정상 처리되는지** 확인한다.
4. **재현 실험 재실행**: `docs/2026-08-17-02-test-results-sse-hol-blocking.md`와 같은
   조건(느린 클라이언트 1개 + 지속적 위치 전송)으로 60초 이상 유지해보고, 이번엔
   `TaskRejectedException`이 더 이상 발생하지 않는지 확인한다.

## 결정이 필요한 것

- `1,000`이라는 permit 수는 기존 큐 상한을 그대로 가져온 값이라 실측 근거가 약하다 — 위
  검증 계획 2번의 기준선이 나오면 여유를 둔 값으로 조정할지 판단한다.
- `sse.fanout.dropped`에 대한 알림/대시보드 연동 여부 — 지금은 지표 노출까지만 하고, 실제
  알림(페이징) 임계값 설정은 별도 판단 필요.
