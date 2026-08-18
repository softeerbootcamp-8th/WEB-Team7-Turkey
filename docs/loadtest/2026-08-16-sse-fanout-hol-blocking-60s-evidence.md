# SSE 팬아웃 HOL Blocking — 60초 쓰기 타임아웃과 메시지 유실 재현 근거

## 목적

`#502` 힙 튜닝 이후 논의된 "가상 스레드 전환 시 `trackingEventExecutor`(Redis pub/sub → SSE 팬아웃
전용 고정 풀, 4개) 백프레셔를 어떻게 대체할지" 디스커션의 근거 자료. 느린 SSE 클라이언트가
이 풀을 막았을 때 (1) 정말 무한정 블로킹되는지 (2) 그 사이 다른 배송에 어떤 영향이 있는지를
실측했다.

## 재현 방법

`backend/loadtest/local/slow-sse-client.py` (본 저장소 신설 스크립트)로 SSE 연결을 맺은 뒤
`recv()`를 호출하지 않는 "안 읽는 클라이언트" 1개를 만들고, 같은 배송에 `rider-location-update.js`
(RIDER_COUNT=1, 닫힌 모델 최대 속도, 약 1,500 req/s)로 위치 갱신을 계속 흘렸다. **반드시
`backend_default` docker network 안에서 실행**했다 — 호스트 매핑 포트로 재면 Docker Desktop의
호스트↔VM 포트포워딩 계층이 끼어들어 타이밍이 왜곡된다(최초 재현에서 19.4초로 잘못 측정된 원인).

```
docker run --rm --network backend_default \
  -v "$(pwd)/loadtest/local:/scripts:ro" python:3.12-alpine \
  python3 /scripts/slow-sse-client.py --host app --port 8080 \
  --actuator-host app --actuator-port 8081 --slow 1 --hold-seconds 320
```

앱 로그는 `docker compose logs -f app`를 별도 파일로 계속 받아두고(초당 ~1,500 req 트래픽이라
도커 로그 버퍼가 몇 분 안에 회전·유실되므로, 사후 조회로는 놓친다) 사후에 검색했다.

## 결과: 정확히 60초, 세 번 재현 동일

| 시도 | 포화 시작(executor_active_threads=4 고정) | 회복 시점 | 경과 |
|---|---|---|---|
| 1차(호스트 매핑, 참고용·부정확) | - | - | 19.4초(왜곡됨) |
| 2차(docker network 내부) | 11:55:39 | 11:56:39 | **60초** |
| 3차(docker network 내부) | 12:04:24 | 12:05:24 | **60초** |

## 로그 근거

**회복 순간 — 4개 스레드가 동시에 `IOException`으로 실패 처리됨:**

```
2026-08-16T12:05:24.220Z  WARN [   sse-fanout-1] c.t.q.location.sse.SseRelay : event=SSE_SEND_FAILED orderId=22274 reason=CLIENT_DISCONNECTED
2026-08-16T12:05:24.220Z  WARN [   sse-fanout-2] c.t.q.location.sse.SseRelay : event=SSE_SEND_FAILED orderId=22274 reason=CLIENT_DISCONNECTED
2026-08-16T12:05:24.220Z  WARN [   sse-fanout-4] c.t.q.location.sse.SseRelay : event=SSE_SEND_FAILED orderId=22274 reason=CLIENT_DISCONNECTED
2026-08-16T12:05:24.221Z  WARN [   sse-fanout-3] c.t.q.location.sse.SseRelay : event=SSE_SEND_FAILED orderId=22274 reason=CLIENT_DISCONNECTED
```

포화 시작(12:04:24.940, 첫 드롭 로그) → 회복(12:05:24.220) = **59.28초**, Tomcat NIO 커넥터의
쓰기 타임아웃(`SocketWrapperBase.writeTimeout`, `NioEndpoint`가 `getConnectionTimeout()`으로
설정)이 만료된 것으로 보인다.

**그 60초 동안 — 무관한 배송의 메시지가 큐에 들어가지도 못하고 그 자리에서 유실됨(73,956건):**

```
2026-08-16T12:04:24.940Z ERROR [ioEventLoop-4-2] io.lettuce.core.pubsub.PubSubEndpoint : Unexpected error occurred in RedisPubSubListener callback

org.springframework.core.task.TaskRejectedException: ExecutorService in active state did not accept task: org.springframework.data.redis.listener.RedisMessageListenerContainer$$Lambda/0x0000001802379c08@5e43d3c7
	at org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.execute(ThreadPoolTaskExecutor.java:388) ~[spring-context-7.0.8.jar!/:7.0.8]
	at org.springframework.data.redis.listener.RedisMessageListenerContainer.dispatchMessage(RedisMessageListenerContainer.java:1007) ~[spring-data-redis-4.1.0.jar!/:4.1.0]
	at org.springframework.data.redis.listener.RedisMessageListenerContainer$DispatchMessageListener.onMessage(RedisMessageListenerContainer.java:1193) ~[spring-data-redis-4.1.0.jar!/:4.1.0]
	at org.springframework.data.redis.listener.SynchronizingMessageListener.onMessage(SynchronizingMessageListener.java:63) ~[spring-data-redis-4.1.0.jar!/:4.1.0]
	at org.springframework.data.redis.connection.lettuce.LettuceMessageListener.message(LettuceMessageListener.java:50) ~[spring-data-redis-4.1.0.jar!/:4.1.0]
	at org.springframework.data.redis.connection.lettuce.LettuceMessageListener.message(LettuceMessageListener.java:31) ~[spring-data-redis-4.1.0.jar!/:4.1.0]
	at io.lettuce.core.pubsub.PubSubEndpoint.notifyListeners(PubSubEndpoint.java:259) ~[lettuce-core-7.5.2.RELEASE.jar!/:7.5.2.RELEASE/5728917]
	at io.lettuce.core.pubsub.PubSubEndpoint.notifyMessage(PubSubEndpoint.java:245) ~[lettuce-core-7.5.2.RELEASE.jar!/:7.5.2.RELEASE/5728917]
	at io.lettuce.core.pubsub.PubSubCommandHandler.doNotifyMessage(PubSubCommandHandler.java:283) ~[lettuce-core-7.5.2.RELEASE.jar!/:7.5.2.RELEASE/5728917]
	at io.lettuce.core.pubsub.PubSubCommandHandler.notifyPushListeners(PubSubCommandHandler.java:208) ~[lettuce-core-7.5.2.RELEASE.jar!/:7.5.2.RELEASE/5728917]
	at io.lettuce.core.protocol.CommandHandler.decode(CommandHandler.java:658) ~[lettuce-core-7.5.2.RELEASE.jar!/:7.5.2.RELEASE/5728917]
	(... Netty I/O 스레드까지 이어지는 스택, 생략)
```

이 예외는 `SseRelay`가 아니라 **Redis 클라이언트(Lettuce)의 메시지 콜백 스택 안에서** 발생한다 —
`RedisMessageListenerContainer.dispatchMessage()`가 큐 가득 찬 `trackingEventExecutor`에
`execute()`를 호출하면서 `TaskRejectedException`을 던지고, 이게 Lettuce의 `PubSubEndpoint`
최상위 catch-all에 잡혀 `"Unexpected error occurred in RedisPubSubListener callback"`으로만
남는다. 발생 건수(3차 재현, 12:04:24.940~12:05:24.219 구간): **73,956건.**

## 결론

1. **무한 블로킹은 아니다** — Tomcat이 쓰기 시도당 60초 타임아웃을 갖고 있어 결국은 정리된다.
2. **그러나 그 60초 동안 무관한 배송의 위치 갱신이 큐에 쌓이는 게 아니라 즉시 유실된다** — 원인이
   된 느린 연결과 전혀 상관없는 다른 모든 배송이 영향을 받는다.
3. 유실 로그가 `SseRelay`가 아니라 Lettuce 내부 콜백에 `ERROR` 레벨로 찍혀, 평소 모니터링에서
   놓치기 쉽다.
4. 가상 스레드 전환은 "일꾼 수 상한" 자체를 없애 이 경로를 원천 차단한다 — 세마포어 기반
   백프레셔 제안(디스커션 본문)의 근거.

## 참고

- 1차(부정확) 측정치 19.4초는 호스트 매핑 포트를 거쳐 쟀기 때문이며, 재현 시 반드시
  docker network 안에서 실행할 것(`loadtest` 스킬 원칙 2와 동일한 함정).
- 원본 앱 로그 전량(약 3.18M줄, 489MB)은 세션 임시 파일(`/tmp/app-capture.log`)에만 있었고
  용량 문제로 보존하지 않았다 — 위 발췌가 재현·검증에 필요한 전부다. 동일한 로그가 다시
  필요하면 위 재현 방법대로 다시 뽑아낼 수 있다.
