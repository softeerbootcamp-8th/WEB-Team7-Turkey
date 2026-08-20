# 테스트 결과: 느린 클라이언트 재현과 60초간의 메시지 유실

배경 개념(스레드풀, TCP 소켓 버퍼, Zero Window)은
`docs/2026-08-17-01-study-sse-threadpool-and-slow-clients.md`를 먼저 읽으면 이해가 쉽습니다.
이 문서는 그 개념이 실제 코드에서 어떻게 나타나는지 직접 재현하고 측정한 결과입니다.

## 확인하고 싶었던 것

1. 무관한 배송을 추적 중인 고객이, 다른 배송의 "느린 클라이언트" 때문에 실제로 영향을
   받는가?
2. 영향을 받는다면 얼마나 오래 지속되는가 — 정말 무한정 멈추는가, 아니면 어딘가 안전장치가
   있는가?
3. 그 안전장치가 있다면, 그동안 아무 부작용 없이 조용히 넘어가는가?

## 재현 도구

`backend/loadtest/local/slow-sse-client.py`를 새로 만들었습니다. k6의 SSE 확장(`xk6-sse`)은
내부적으로 소켓을 계속 읽어들이기 때문에 "안 읽는 클라이언트"를 절대 만들 수 없습니다 —
그래서 표준 라이브러리 소켓을 직접 열고, 핸드셰이크만 끝낸 뒤 의도적으로 `recv()`를 다시
호출하지 않는 방식으로 만들었습니다. TCP 수신 버퍼를 인위적으로 작게(`SO_RCVBUF`) 잡아서
Zero Window 상태에 빨리 도달하게 했습니다.

```bash
docker run --rm --network backend_default \
  -v "$(pwd)/loadtest/local:/scripts:ro" python:3.12-alpine \
  python3 /scripts/slow-sse-client.py --host app --port 8080 \
  --actuator-host app --actuator-port 8081 --slow 5 --hold-seconds 45
```

## 방법론적 주의사항 — 반드시 같은 docker network 안에서 잴 것

첫 재현은 호스트에 매핑된 포트(`localhost:8083`)로 접속해서 쟀는데, 이때 "19.4초 뒤에
회복됐다"는 결과가 나왔습니다. 하지만 이 수치는 부정확했습니다 — 호스트↔컨테이너 포트
포워딩 계층(Docker Desktop의 VM 경계)이 타이밍에 끼어들기 때문입니다. 이건 이 저장소의
부하테스트 원칙("측정 경로는 한 docker 네트워크 안에서 끝낸다")과 정확히 같은 함정이고,
직접 겪고 나서야 그 원칙의 무게를 실감했습니다. 아래 결과는 전부 같은 docker network
안에서 다시 측정한, 신뢰할 수 있는 값입니다.

## 결과 1 — 무관한 고객이 실제로 영향을 받는다

BUSY 라이더 6명 중 5명을 추적하는 고객은 연결만 걸고 안 읽게 하고, 6번째(다른 5명과
아무 관계 없는 배송)를 추적하는 고객은 정상적으로 이벤트를 받으며 지연을 측정했습니다.

| 구간 | `executor_active_threads` | `executor_queued_tasks` | 6번째 고객의 지연 |
|---|---|---|---|
| 느린 클라이언트 연결 전 | 0 / 4 | 0 | ~0ms |
| 느린 클라이언트 5개 연결 중 | 4 / 4 (풀 상한) | 1000 / 1000 (큐 상한) | 최대 19,426ms |

느린 클라이언트가 끊기자 곧바로 0 / 0으로 복귀했고, 서버는 죽지 않았습니다. 이 값(19.4초)은
위 방법론 주의사항 때문에 정확한 절대값으로 쓰면 안 되고, "영향이 실제로 있다"는 걸 보여주는
1차 증거로만 씁니다.

## 결과 2 — 정확한 값은 60초 (같은 네트워크 안에서 3회 재현)

| 시도 | 포화 시작 | 회복 시점 | 경과 |
|---|---|---|---|
| docker network 내부, 1차 | 11:55:39 | 11:56:39 | 60초 |
| docker network 내부, 2차 | 12:04:24 | 12:05:24 | 60초 |

회복 순간 앱 로그:

```
2026-08-16T12:05:24.220Z WARN [sse-fanout-1] SseRelay: event=SSE_SEND_FAILED orderId=22274 reason=CLIENT_DISCONNECTED
2026-08-16T12:05:24.220Z WARN [sse-fanout-2] SseRelay: event=SSE_SEND_FAILED orderId=22274 reason=CLIENT_DISCONNECTED
2026-08-16T12:05:24.220Z WARN [sse-fanout-4] SseRelay: event=SSE_SEND_FAILED orderId=22274 reason=CLIENT_DISCONNECTED
2026-08-16T12:05:24.221Z WARN [sse-fanout-3] SseRelay: event=SSE_SEND_FAILED orderId=22274 reason=CLIENT_DISCONNECTED
```

이건 우리가 쓰는 웹 서버(Tomcat)가 "소켓에 60초 넘게 못 쓰면 포기한다"는 자체 쓰기
타임아웃을 갖고 있기 때문입니다. 그러니 무한정 멈추는 건 아닙니다.

## 결과 3 — 그런데 그 60초 동안, 무관한 데이터가 조용히 사라지고 있었다

같은 60초 구간의 로그를 전부 뒤져보니, 다음과 같은 예외가 73,956번 찍혀 있었습니다.

```
2026-08-16T12:04:24.940Z ERROR [ioEventLoop-4-2] io.lettuce.core.pubsub.PubSubEndpoint : Unexpected error occurred in RedisPubSubListener callback

org.springframework.core.task.TaskRejectedException: ExecutorService in active state did not accept task
	at ...ThreadPoolTaskExecutor.execute
	at ...RedisMessageListenerContainer.dispatchMessage
	at ...LettuceMessageListener.message
	at io.lettuce.core.pubsub.PubSubEndpoint.notifyListeners
	(... 이하 생략, 전체는 raw 근거 파일 참고)
```

스레드 4개가 전부 막혀 있고 대기줄(1000)도 이미 가득 찬 상태라, 그 사이 도착한 다른
배송의 위치 갱신은 대기줄에 들어가지도 못하고 그 자리에서 즉시 버려졌습니다. 이건 응답
없는 그 고객과는 아무 상관도 없는, 60초 동안 있었던 다른 모든 정상 배송의 데이터입니다.

이 예외는 우리 코드(`SseRelay`)가 아니라 Redis 클라이언트 라이브러리(Lettuce)의 내부
콜백 스택 안에서 나기 때문에, 평소 로그를 볼 때 눈에 잘 안 띄는 문구(`"Unexpected error
occurred..."`)로 찍힙니다.

원본 로그 발췌·전체 스택트레이스·재현 커맨드는
[`docs/loadtest/2026-08-16-sse-fanout-hol-blocking-60s-evidence.md`](loadtest/2026-08-16-sse-fanout-hol-blocking-60s-evidence.md)에 있습니다.

## 결과 4 — 같은 이유로 HikariCP(DB 커넥션 풀)도 위험한가?

가상 스레드로 바꾸는 논의가 나온 김에, HikariCP도 비슷한 문제가 있는지 확인했습니다.
결론은 다른 종류의 위험이고, 실측으로는 지금 안전함이 확인됐습니다.

- HikariCP 풀 크기(10)는 SSE 풀(4)과 달리 인위적 제한이 아니라 실제 DB 커넥션 개수
  제한이라, 가상 스레드로 없앨 대상이 아닙니다.
- 대신 JDBC 드라이버가 `synchronized` 블록 안에서 블로킹 I/O를 하면, 가상 스레드가
  자신을 실행하던 진짜 OS 스레드(carrier thread)를 놓지 못하는 핀닝(pinning)이라는
  별도 위험이 있습니다. carrier thread는 애플리케이션 전체가 공유하는 자원이라, 이게
  터지면 SSE 풀 문제보다 훨씬 광범위하게(애플리케이션 전체가) 멈출 수 있습니다.
- JDK의 공식 진단 플래그(`-Djdk.tracePinnedThreads=full`)를 켜고, DB를 매번 3번씩 두드리는
  API에 가상 스레드 50개를 동시에 투입해 730,801건을 처리시켰습니다. 핀닝 경고 0건.
  지금 버전(mysql-connector-j 9.7.0, HikariCP 7.0.2, JDK 21.0.11) 조합과 이 프로젝트의
  쿼리 패턴에서는 문제가 없다는 뜻입니다.
- 단, 더 느린 쿼리나 다른 드라이버 버전까지 일반화할 순 없습니다. 상세 방법·한계는
  [`docs/loadtest/2026-08-17-hikari-virtual-thread-pinning-evidence.md`](loadtest/2026-08-17-hikari-virtual-thread-pinning-evidence.md) 참고.

## 종합

| 질문 | 답 |
|---|---|
| 무관한 고객이 영향을 받는가? | 받는다 (실측 확인) |
| 무한정 멈추는가? | 아니다 — 정확히 60초 (Tomcat 쓰기 타임아웃) |
| 그 60초 동안 부작용 없이 조용히 넘어가는가? | 아니다 — 그 사이 도착한 무관한 메시지 73,956건이 즉시 유실됨 |
| HikariCP도 같은 문제가 있는가? | 다른 종류의 위험(핀닝)이 있지만, 실측으로는 지금 확인 안 됨(조건부 안전) |

이 결과를 바탕으로 한 해결 방안은 `docs/2026-08-17-03-discussion-virtual-thread-migration.md`에서 다룹니다.
