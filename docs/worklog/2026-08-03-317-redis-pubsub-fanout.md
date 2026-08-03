# 라이더 최신 위치 Redis 저장 + SSE Pub/Sub 팬아웃 재도입 작업 기록

- 이슈: [#317](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/317)
- 브랜치: `feature/317-redis-pubsub-fanout`
- 범위: backend
- 작성일: 2026-08-03

## 무엇을 만들었나

스케일 아웃 대비로, #297(위치 추적 단순화)에서 걷어낸 것 중 **두 가지만** 되돌렸다.
① 라이더 위치 갱신이 Redis Pub/Sub 채널(`tracking:order:{deliveryId}`)로 발행되고, 모든 인스턴스가
패턴 구독으로 받아 **자기 JVM 이 들고 있는 SSE 연결로만** 내보낸다. ② 라이더 최신 위치를
Redis 에 조건부(측정 시각 비교) 저장한다.

서버가 위치를 **검증·필터링하지 않는다는 #297 의 방향은 그대로 유지**했다. 필터·MySQL 이력·
heartbeat·연결 수 제한은 되살리지 않았다.

원본은 PR #266(`e04b35a^`)이고, 채널·리스너 컨테이너·Lua 스크립트·2인스턴스 테스트 지원 클래스는
그 커밋에서 꺼내 썼다. 새로 설계한 것은 없다.

### API

계약 변경 없음. `POST /api/rider/location` 과
`GET /api/customer/deliveries/{deliveryId}/tracking/stream` 의 요청·응답·상태코드가 모두 그대로다.
바뀐 것은 **전달 경로**뿐이다(로컬 Map 직접 순회 → Redis 발행 → 전 인스턴스 구독 → 로컬 전송).

SSE 프레임 형식도 그대로다 — 이벤트 이름 없는 기본 `message`, `data:` 에 위치 JSON.
그래서 프론트(`useTrackingStream.onmessage` + `parseLocationPing`)는 한 줄도 고치지 않았다.

### 화면

해당 없음.

### 스키마 변경

해당 없음. Redis 키만 추가된다(`rider:location:{riderId}`, TTL 10분).

## 사람이 고른 선택

### 1. 최신 위치 저장 방식 — 단순 `SET EX` vs Lua 조건부 갱신

- **물었던 것**: 저장된 값보다 최신일 때만 쓰는 조건부 갱신을 되살릴지, 무조건 덮어쓸지.
- **선택지**:
  - (A) 단순 `SET ... EX` — 코드가 절반 이하. 대가: 인스턴스 간 경쟁에서 오래된 좌표가 최신을
    덮을 수 있다(응답은 200이라 조용하고, 다음 전송이 복구한다)
  - (B) `e04b35a^` 의 Lua CAS(`saveIfNewer`) 복원 — 고정폭 인코딩·정규식 검증·Lua 스크립트가
    따라온다. 대가: 지금 저장값을 읽는 코드가 없어 그 정합성의 효용을 확인할 수단도 없다
- **고른 것**: (B)
- **근거**: 오래된 좌표가 최신을 덮는 것을 원천 차단한다. 단순 `SET EX` 는 다음 전송이 복구하지만
  **조용히 틀린 구간이 생긴다.**
- **영향**: 값 형식이 `measuredAt,latitude,longitude,accuracyMeters` 로 고정되고, **측정 시각
  접두어가 항상 23자**여야 한다(Lua 가 그 폭을 잘라 사전순 비교한다). 이 폭이 깨지면 조건부 갱신이
  조용히 오작동하므로 단위 테스트가 폭과 "사전순 = 시간순"을 함께 고정해 둔다.
  포맷터에 UTC 를 못 박은 것도 같은 이유다 — 인스턴스마다 기본 시간대가 다르면 같은 순간이 다른
  문자열이 되어 비교가 무의미해진다.

### 2. 저장한 최신 위치를 읽는 쪽을 이번에 만들지

- **물었던 것**: 지금 저장값을 읽는 코드가 하나도 없다(추적 스냅샷 API 에 좌표 필드가 없다).
  소비자를 함께 만들지.
- **선택지**:
  - (A) SSE 연결 시 `init` 스냅샷 이벤트 — 컨트롤러 3줄. 재접속·새로고침 직후의 마커 공백이 사라진다
  - (B) 저장만 하고 소비자는 다음 이슈 — 요청 범위를 문자 그대로 지킨다
  - (C) #311 폴링 API 까지 — 이미 열려 있는 이슈이고 `RiderLocationStore.find` 를 전제로 작성돼 있다
- **고른 것**: 처음 (C) 를 골랐다가 **(B) 로 번복**("polling 용 api 는 개발하지 마세요").
- **근거**: 이번 범위는 저장과 팬아웃까지다.
- **영향**: 읽는 쪽이 없으므로 **`find()`·`decode()` 도 만들지 않았다** — 호출자 없는 getter 를 미리
  두지 않는다는 판단이다. 그래서 저장값의 형식 계약을 지키는 것은 `encode` 단위 테스트와 실제 Redis
  통합 테스트(원시 문자열을 직접 읽어 비교)뿐이다. 소비자가 생기는 이슈에서 `find`/`decode` 를
  함께 추가해야 한다.

### 3. 이슈·브랜치 처리

- **물었던 것**: CLAUDE.md 에 "Pub/Sub 재도입은 아직 이슈가 없다"고 적혀 있었고 실제로 없었다.
- **선택지**: (A) 새 이슈 생성 / (B) 이슈 없이 브랜치만 / (C) 닫힌 #78 재사용
- **고른 것**: (A) → #317 생성.
- **근거**: 절차를 유지해 나중에 근거를 캘 수 있게 한다.
- **영향**: 프로젝트 보드 Status 이동은 **하지 못했다** — `gh` 토큰에 `read:project` 스코프가 없다
  (`gh auth refresh -s read:project,project` 필요). 수동으로 옮겨야 한다.

## 스스로 판단한 것

- **`RiderLocationStore` 인터페이스와 인메모리 대체를 되살리지 않았다** — 이 클래스에서 검증할
  가치가 있는 성질이 "Redis 서버 한 곳에서 스크립트가 직렬화 실행된다"는 것이라 인메모리로는
  재현이 불가능하다. 어차피 실제 Redis 통합 테스트가 필요하므로 인터페이스의 존재 이유가 없다.
  같은 패키지의 `RiderGeoRepository`(구현체 하나, 인터페이스 없음)와 관례를 맞췄다.

- **발행 경로에 MySQL 조회를 넣지 않았다** — 옛 `RedisTrackingEventPublisher` 는 채널 키(주문)를
  만들기 위해 `findInProgressByRiderId` 를 불렀고 그 javadoc 이 길게 그 대가를 변호했다. #290 이후
  `deliveryId` 가 요청 본문으로 오므로 그 조회 자체가 사라진다. 대가는 그대로 남는다 — **라이더가
  그 배송에 실제로 배정됐는지는 여전히 검증하지 않는다**(#291 에서 알려진 구멍, 별건).

- **`SseRelay.publish` 가 객체가 아니라 JSON 문자열을 받게 바꿨다** — 구독자가 역직렬화→재직렬화
  하지 않고 그대로 흘린다. 페이로드 계약이 발행 지점 한 곳에만 존재하는 대신, **롤링 배포 중
  구·신 형식이 그대로 클라이언트로 나갈 수 있다**(필드 추가만 허용, 제거·의미 변경 금지 — Flyway,
  Redis 값 형식에 이은 세 번째 배포 호환성 표면).

- **컨트롤러에 서비스 계층을 새로 만들지 않았다** — #297 이후 이 경로에 서비스가 없고, 늘어난 것이
  Redis 쓰기 한 줄이다. GEO 후보 반영과 최신 위치 저장을 **하나의 `try/catch` 로 묶었다**(둘 다
  Redis 쓰기이고 실패 처리가 같다). 순서는 GEO 먼저다 — 배차 후보는 실제로 쓰이는 값이고, 최신
  위치는 아직 읽는 쪽이 없어 스크립트 오류가 그쪽에 번지지 않게 했다.

- **`SecondaryInstance` 에 `--management.server.port=0` 을 추가했다** — 원본에는 없다. 그 뒤에
  들어온 모니터링 인프라(#315)가 `management.server.port: 8081` 을 고정해서, 두 인스턴스가 같은
  관리 포트를 잡으려다 기동이 깨진다.

- **`SseTestClient.awaitData` 를 추가했다** — 위치 프레임에 이벤트 이름이 없어 기존
  `awaitEvent(name, …)` 로는 기다릴 수 없다. 이름을 붙이지 않는 것이 프론트 `onmessage` 계약이므로
  테스트 쪽을 맞췄다.

## 일부러 하지 않은 것

- **#311 고객 위치 조회 폴링 API**: 사람 지시로 범위에서 뺐다 — 후속: #311
- **SSE `init` 스냅샷 이벤트**: 위 선택 2 — 후속: 미등록(저장값 소비자가 필요해지는 시점)
- **서버측 위치 필터**(`LocationAcceptancePolicy`·`LocationFilter`): #297 의 방향을 유지 — 후속: #82, #248, #249
- **heartbeat 와 `@EnableScheduling`**: 되살리지 않았다. TTL 5분 동안 조용한 스트림이 프록시 유휴
  타임아웃에 끊길 수 있는 위험은 그대로다 — 후속: 미등록
- **배송당 연결 수 제한**(옛 ZSET+Lua, 3개): 필요성이 재확인되기 전까지 만들지 않기로 한 기존 결정
  유지 — 후속: 미등록
- **`refreshTtl`**: 유일한 호출자였던 서버 필터가 없다 — 후속: 필터 이슈와 함께
- **MySQL 위치 이력**(`rider_location_history`): 테이블만 있고 쓰는 코드가 없는 상태 유지 — 후속: #102
- **프로파일별 채널 접두어 분리**: 원본과 같이 감내했다(아래 미결) — 후속: 미등록

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `location/sse/TrackingChannelTest` | 채널명·구독 패턴 형식 고정, 잘못된 채널명은 예외가 아니라 빈 결과(리스너 스레드에서 던지면 그 메시지의 나머지 수신자를 잃는다) |
| 단위 | `location/sse/TrackingPublisherTest` | 올바른 채널로 발행, **`measuredAt` 이 문자열로 직렬화**(숫자가 되면 프론트가 프레임을 통째로 버린다), Redis 예외를 삼킴 |
| 단위 | `location/sse/TrackingSubscriberTest` | 원본 JSON 무변경 전달, 파싱 불가 채널 drop, 빈 본문도 해석하지 않고 전달 |
| 단위 | `location/sse/SseRelayTest` | 문자열 페이로드 전송, 미구독 no-op, 실패 emitter 축출, emitter 간 실패 격리 |
| 단위 | `location/repository/RiderLocationRepositoryTest` | `encode` 필드 순서·빈 정확도, **접두어 항상 23자**, **사전순 = 시간순**(자정·해 경계 포함) |
| 단위 | `location/controller/RiderLocationUpdateControllerTest` | 운행 상태별 GEO 반영, 상태 무관 최신 위치 저장, 배송 채널 발행, **Redis 실패(GEO·저장 각각)에도 발행과 200 유지** |
| 통합 | `location/repository/RiderLocationRepositoryIntegrationTest` | 실제 Redis 로 조건부 갱신: 최초/최신/과거/동일 시각/밀리초 경계/손상된 값 덮어쓰기/TTL 원자 설정/거절 시 TTL 미연장. **16스레드 경쟁**: 같은 시각이면 정확히 1개만 성공, 다른 시각이면 최종값이 항상 최신(5라운드) |
| E2E | `location/sse/TrackingFanoutMultiInstanceE2ETest` | **A 연결 → B POST → A 도달**(팬아웃을 증명하는 유일한 테스트), 같은 인스턴스 경로, 프론트 파서 계약, 다른 배송 구독자에게 누출 없음, A 세션으로 B API 호출(스티키 세션 불필요) |
| E2E | `location/controller/CustomerTrackingStreamE2ETest` | 기존 검증 유지 + 끊긴 연결 정리를 실제 실패 경로로 |
| E2E | `location/controller/RiderLocationUpdateE2ETest` | 기존 검증 유지 + POST 후 Redis 에 최신 위치와 TTL 이 남음 |

실행 결과:

```text
cd backend && docker compose up -d      # mysql:8.4, redis:7.4 (healthy)
./gradlew test → BUILD SUCCESSFUL, 459 tests, 0 failures, 0 errors, 0 skipped
cd frontend && pnpm typecheck → tsc -b --noEmit, 출력 없음(통과)
```

**팬아웃 테스트의 반증 실험도 실제로 돌렸다.** `TrackingPublisher` 를 Redis 대신 로컬 relay 로 직접
전달하도록 임시 교체했더니 교차 인스턴스 테스트 3개(`deliversAcrossInstances`,
`deliveredFrameMatchesFrontendContract`, `doesNotLeakToOtherDeliverySubscribers`)만 실패하고
같은 인스턴스·세션 공유 테스트는 통과했다 — 이 테스트가 팬아웃을 실제로 증명한다는 확인이다.

### 검증하지 못한 것

- **실제 TTL 만료**(10분 뒤 키가 사라지는 것) — 대기 시간 때문에 여전히 검증하지 않는다.
- **진짜 프로세스 분리** — `SecondaryInstance` 는 같은 JVM 의 두 번째 `ApplicationContext` 다.
  `static` 상태로 인한 거짓 통과는 "두 레지스트리가 다른 객체" 단언으로 부분적으로만 막힌다.
  롤링 배포 중 구·신 버전 공존은 이 방식으로 재현할 수 없다.
- **CloudFront 를 통한 스트리밍** — 팬아웃과 무관하게 배포 후 `curl -N` 확인이 남아 있다(#266 부터).
- **ElastiCache 클러스터 모드** — 로컬 단일 Redis 로만 검증했다.

## 새로 생긴 미결 사항

- **끊긴 연결 탐지에는 쓰기가 최소 두 번 필요하다**(#317 에서 실측). heartbeat 가 없어 서버는
  클라이언트가 닫은 것을 스스로 모르고, 끊긴 연결에 대한 **첫 쓰기는 소켓 버퍼에 들어가 성공하는
  경우가 많다.** 그래서 탭을 닫은 고객의 연결은 위치 전송 두 주기(BUSY 5초 기준 약 10초)까지
  레지스트리에 남고, 최종 상한은 emitter 타임아웃 5분이다. 기존 E2E 가 "쓰기 1회 → 실패"에
  의존하고 있었는데 Redis 홉이 끼면서 드러났다(테스트를 발행 반복으로 고쳤다).
- **Pub/Sub 채널이 로직 DB 로 격리되지 않는다**(원본 #78 의 문제 재발). 채널은 `SELECT` 를 무시하므로
  **테스트(DB 1)와 개발용 앱(DB 0)이 같은 채널을 공유한다.** `DatabaseCleaner` 의 TRUNCATE 가
  AUTO_INCREMENT 를 리셋해 테스트 배송이 매번 낮은 id 를 받으므로, 개발용 앱을 띄운 채 테스트를
  돌리면 채널명이 겹칠 수 있다. 프로파일별 접두어 분리는 미결.
- **ElastiCache 클러스터 모드를 켜면 이 팬아웃이 동작하지 않는다**(원본 #78). cluster mode enabled
  에서는 Redis 7 의 sharded pub/sub(`SSUBSCRIBE`)이 필요한데 `RedisMessageListenerContainer` 는
  일반 pub/sub 만 다루고 `PSUBSCRIBE` 는 클러스터에서 문제가 된다. **클러스터 모드 비활성 전제.**
- **저장한 최신 위치를 읽는 코드가 없다.** 소비자(#311 폴링 arm 또는 SSE `init`)를 만드는 이슈에서
  `find`/`decode` 를 추가해야 한다. 그때까지 값 형식은 `encode` 테스트로만 지켜진다.
- **팬아웃 디스패처 풀 크기 4가 적절한지 미검증.** 같은 채널 메시지의 처리 순서가 뒤집힐 수 있고,
  그 복구는 프론트가 `measuredAt` 이 역행하는 이벤트를 버리는 것에 의존한다 — **현재
  `useTrackingStream` 에 그 가드가 없다.** 부하 테스트에서 확인할 항목.
- **`server.shutdown` 미결이 그대로다.** SSE 가 끝나지 않아 종료마다 graceful 대기 30초를 소진한 뒤
  강제 중단된다(2인스턴스 테스트에서 그대로 관측됐다). 팬아웃 executor 는
  `waitForTasksToCompleteOnShutdown=false` 라 이 대기에 더하지 않는다.
- **프로젝트 보드 Status 를 옮기지 못했다** — `gh` 토큰에 `read:project` 스코프가 없다.
