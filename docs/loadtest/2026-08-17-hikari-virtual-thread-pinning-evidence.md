# HikariCP + 가상 스레드 핀닝(pinning) 확인 근거

## 목적

SSE 팬아웃 전용 스레드풀을 가상 스레드로 바꾸는 디스커션 중, "같은 이유로 HikariCP도 문제가
생기지 않는가"라는 질문에 답하기 위한 실측. HikariCP 풀 크기(10)는 SSE 풀(4)과 달리 **인위적
제한이 아니라 실제 DB 커넥션 개수 제한**이라 가상 스레드로 없앨 대상이 아니고, 대신 JDBC
드라이버가 `synchronized` 블록 안에서 블로킹 I/O를 하면 가상 스레드가 자신을 실행하던 OS
스레드(carrier thread)를 놓지 못하는 **핀닝(pinning)** 위험이 별도로 있다. JDK 21은 이 핀닝이
아직 수정되지 않은 버전(수정은 JDK 24, JEP 491)이라 실제로 발생하는지 확인이 필요했다.

## 환경

- JDK: `openjdk 21.0.11` (Temurin) — 컨테이너에서 `java -version`으로 직접 확인
- `com.mysql:mysql-connector-j:9.7.0` (Gradle `dependencyInsight`로 확인)
- `com.zaxxer:HikariCP:7.0.2` (Gradle `dependencyInsight`로 확인)
- `spring.threads.virtual.enabled: true` (application.yml)
- Hikari 설정: `maximum-pool-size: 10`, `minimum-idle: 10` (기본값, 변경 없음)

## 방법

JDK 공식 진단 플래그 `-Djdk.tracePinnedThreads=full`을 켰다. 이 플래그는 가상 스레드가 핀닝될
때마다 즉시 스택트레이스를 표준출력에 찍어준다 — 별도 라이브러리나 APM 없이 JDK 자체가 제공하는
정확한 신호다.

```bash
cd backend
docker compose up -d --force-recreate app   # JAVA_GC_OPTS="-Djdk.tracePinnedThreads=full" 로 재기동
```

`JAVA_GC_OPTS` 환경변수는 Dockerfile 주석에 "진단 플래그용"으로 이미 마련돼 있어 별도 이미지
재빌드 없이 켤 수 있었다. 실제 기동 커맨드(`/proc/1/cmdline`으로 확인):

```
java -XX:+UseG1GC -Xms512m -Xmx512m -Djdk.tracePinnedThreads=full \
  -XX:+HeapDumpOnOutOfMemoryError ... -jar app.jar
```

부하는 DB를 매 요청 3회 두드리는 `POST /api/rider/location`(`rider-location-update.js`)에
가상 스레드 50개를 동시에 투입해 만들었다(BUSY 라이더 50명, `RIDER_COUNT=50 MAX_VU=50`).

```bash
docker compose --profile loadtest run --rm \
  -e BASE_URL=http://app:8080 -e RIDER_COUNT=50 -e MAX_VU=50 \
  k6 run --tag testid=pinning-probe-<timestamp> /scripts/local/rider-location-update.js
```

## 결과

| 항목 | 값 |
|---|---|
| 총 요청 수 | 730,801건 |
| 실패율 | 0.00% |
| 동시 VU(=동시 가상 스레드) | 최대 50 |
| `http_req_duration` | avg 미기재, p95 4.87ms, p99 8.28ms, max 98.99ms |
| **핀닝 경고(`-Djdk.tracePinnedThreads`) 발생 건수** | **0건** |

앱 로그에서 스레드 이름이 `t-handler-XXXXX` 형태(가상 스레드 명명 규칙)로 찍히는 것도 확인해
Tomcat 요청 처리 스레드가 실제로 가상 스레드로 도는 상태에서 테스트했음을 교차 확인했다.

## 해석 및 한계

- 지금 버전 조합(mysql-connector-j 9.7.0 + HikariCP 7.0.2 + JDK 21.0.11)과 이 프로젝트의
  실제 쿼리 패턴(인덱스 조회 위주의 짧은 쿼리 3개/요청)에서는 **핀닝이 관측되지 않았다.**
  최신 JDBC 드라이버들이 Loom 대응으로 내부 락 구조를 정리해온 결과로 보인다.
- **검증 범위는 "이 쿼리 패턴, 이 부하 수준"으로 한정된다.** 더 느린 쿼리(락 경합, 긴
  트랜잭션, 대량 스캔)나 다른 JDBC 드라이버 버전에서도 안전한지는 이 테스트로 알 수 없다.
  드라이버 버전을 바꾸거나 느린 쿼리 경로가 새로 추가되면 같은 방법(`tracePinnedThreads`)으로
  재검증해야 한다.
- HikariCP의 커넥션 대기 큐 자체는 `synchronized`가 아니라 `SynchronousQueue` 기반으로 설계돼
  있어(HikariCP 메인테이너 공개 언급) 대기 중 핀닝은 설계상 발생하지 않는다고 알려져 있다 —
  이번 실측은 그 위가 아니라 **쿼리 실행 중 블로킹 I/O** 경로를 확인한 것이다.

## 정리 후 상태

테스트 종료 후 `JAVA_GC_OPTS` 없이 앱을 재기동해 진단 플래그를 제거하고 평상시 상태로 되돌렸다.
