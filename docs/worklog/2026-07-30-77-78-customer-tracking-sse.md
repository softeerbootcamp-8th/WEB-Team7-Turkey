# 고객 실시간 위치 추적 SSE 작업 기록

- 이슈: [#77](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/77) 실시간 위치 구독 시작
  · [#78](https://github.com/softeerbootcamp-8th/WEB-Team7-Turkey/issues/78) 라이더 위치 변경 이벤트 전송
- 브랜치: `feature/77-78-customer-tracking-sse` (base: `feature/250-atomic-location-cas`)
- 범위: backend
- 작성일: 2026-07-30

## 무엇을 만들었나

고객이 자기 배송의 라이더 위치를 실시간으로 보는 경로를 처음부터 끝까지 만들었다. 작업 전 `location/sse/` 는 `.gitkeep` 뿐이었고 `SseEmitter` 가 저장소 전체에 0줄, Redis Pub/Sub 배선도 0줄이었다.

**두 이슈를 한 사이클로 묶었다.** #78 만 하면 받을 곳이 없어 검증이 불가능하고, #77 만 하면 `init` 이후 아무 이벤트도 오지 않아 "실시간"이 증명되지 않는다.

### API

| 메서드 | 경로 | 설명 | 주요 실패 응답 |
|---|---|---|---|
| GET | `/api/customer/deliveries/{deliveryId}/tracking/stream` | 실시간 위치 구독(SSE, `@Hidden`) | 401 세션 / 404 없음·타인 주문 / 409 추적 불가 상태 / 429 연결 한도 / 503 저장소 장애 |
| POST | `/api/rider/location` | **변경 없음.** `published` 가 이제 실제 값을 담는다 | 기존과 동일 |

스트림 이벤트는 `init`(1회) · `location`(갱신마다) · 주석 `:hb`(15초)다. 상세 계약은 `docs/04-frontend-api-map.md §7`.

### 화면

해당 없음. 프론트 연동은 #196(`useTrackingStream` 은 아직 0바이트 빈 파일).

### 스키마 변경

없다. 기존 `delivery_order`(V10)만 읽는다.

## 사람이 고른 선택

### 1. Pub/Sub 채널 키를 주문으로

- **물었던 것**: 발행 채널 키를 무엇으로 잡고, "배송 완료 후 이전 고객에게 라이더 위치가 새는 문제"를 어떻게 닫을까
- **선택지**:
  - (A) 라이더 채널 + heartbeat 재검증 — 발행 경로에 DB 조회가 없다 / 누수 창이 최대 15초 남고, heartbeat 가 성능이 아니라 정합성 장치가 되어 필수로 바뀐다
  - (B) 주문 채널 + 발행 시 DB 조회 — 완료되면 조회가 비어 발행이 즉시 멈춘다(조용해지는 안전한 실패) / 핫패스에 MySQL 조회가 생긴다
- **고른 것**: (B). 그리고 **"주문 완료 전이 코드는 추후에 개발"** 로 확인받았다
- **근거**: 사람 확인. order 채널이면 완료 전이 코드 없이도 누수가 막힌다 — 발행자가 상태 조건과 함께 조회하므로 완료된 배송은 결과가 비어 발행이 그냥 멈춘다.
- **영향**: (B)에 매긴 유일한 비용("DB 를 읽지 않던 핫패스에 조회 추가")이 **어차피 피할 수 없는 것**임을 사람이 지적해 확인했다 — `rider_location_history.order_id` 가 **NOT NULL**(V15)이라 위치 이력 저장(#102)도 같은 POST 경로에서 riderId → orderId 를 풀어야 한다. 라이더 채널을 골랐다면 #102 에서 조회가 되돌아와 "라이더 채널 발행 + 주문 조회"라는 최악의 조합이 됐다. 그래서 조회를 **#78 전용이 아니라 공용 인프라**로 취급하고 `(orderId, status)` 를 함께 돌려준다 — #78 은 추적 가능 4개 상태에 발행하지만 #102 는 `DELIVERING` 만 저장한다.

### 2. heartbeat 도입 (`@EnableScheduling` 신규)

- **물었던 것**: 조용한 구간을 heartbeat 로 메울지, 짧은 타임아웃 + 브라우저 자동 재연결에 맡길지
- **선택지**:
  - (A) emitter 5분 + heartbeat 15초 — 화면이 끊기지 않고, 같은 tick 에서 죽은 연결 정리와 연결 집계 생존 갱신을 얻는다 / 스케줄링 인프라를 새로 도입한다
  - (B) emitter 25초 + heartbeat 없음 — 새 인프라 없음 / 25초마다 재연결하며 세션·주문 조회·카운터 왕복이 다시 돌아 폴링과 비용이 비슷해지고, 재연결 사이 화면이 3~5초 멈춘다
- **고른 것**: (A)
- **근거**: 사람 확인.
- **영향**: 이 결정이 없으면 **연결 한도가 의미를 잃는다.** SSE 는 쓰기를 시도할 때만 끊김을 알 수 있어서, heartbeat 없이는 탭을 닫은 클라이언트의 자리를 emitter 타임아웃(5분)까지 잡고 있다. 커밋 4 에서 그 검증이 실제로 실패해 커밋 5 로 옮겼다.

### 3. CloudFront 설정값 확인

- **물었던 것**: CloudFront 를 통한 SSE 스트리밍을 코드 작성 전에 실측할지
- **고른 것**: 배포 없이 **설정값을 직접 알려 주는 것**으로 갈음
- **받은 값**: Origin Response timeout **최대 120초** / Keep-alive **최대 300초** / HTTP/2·1.1·1.0 지원 / **ALB 는 도입 예정이나 기능 개발 우선**
- **영향**: heartbeat 15초가 안전하다는 것이 확인됐다(기본값 30초든 상한 120초든 절반 미만). emitter 타임아웃 5분 = keep-alive 상한 300초라 재연결 시 커넥션 재사용이 끊기지 않는다. **HTTP/2 지원이 위험 하나를 지웠다** — HTTP/1.1 의 오리진당 6커넥션 제한 때문에 SSE 하나가 다른 API 호출을 굶길 걱정이 없다. ALB 도입 시 idle timeout 기본 60초 > 15초라 그대로 유효하다.
- **남은 것**: **버퍼링 여부는 이 방법으로 알 수 없다.** 아래 「검증하지 못한 것」 참고.

### 4. 2인스턴스 팬아웃 자동 테스트 포함

- **물었던 것**: 1대 테스트는 Pub/Sub 배선을 통째로 빼도 통과하는데, 2인스턴스 테스트를 이번 사이클에 포함할지
- **고른 것**: 포함
- **근거**: 사람 확인.
- **영향**: 이 결정이 옳았다는 것을 실증했다 — 발행을 "직접 전달"로 바꿔 돌렸더니 **교차 케이스만 실패하고 나머지 전부 통과**했다.

## 스스로 판단한 것

- **패턴 구독으로 바꿨다(계획과 다름).** 계획은 주문별 동적 구독(refcount)이었다. 세 이유로 바꿨다: ① 같은 주문이 1→0 과 0→1 을 동시에 겪을 때(탭 닫고 바로 다시 열기) 구독 호출 순서가 뒤집혀 "구독은 없는데 연결은 있다"가 되면 **고객 스트림이 조용히 죽는다** ② `addMessageListener` 는 SUBSCRIBE 왕복이 끝날 때까지 블록해 Redis 지연이 연결 생성 경로에 얹힌다 ③ 구독자와 연결 정리가 서로를 필요로 해 순환 의존이 생긴다. 대가는 모든 인스턴스가 모든 이벤트를 받아 걸러내는 것이고, **채널 이름은 주문별로 유지했으므로** 비용이 문제되면 값 형식을 바꾸지 않고 전환할 수 있다.
- **디스패치 풀을 1이 아니라 4로.** 단일 스레드면 채널 내 순서가 보장되지만 소켓 버퍼가 가득 찬 클라이언트 하나가 **모든 주문의 전달을 멈춘다.** 순서 역전은 프론트의 `measuredAt` 가드로 복구되고(재연결 스냅샷 때문에 어차피 필요하다) 멈춘 디스패처는 복구할 방법이 없어 격리를 골랐다. 그 가드 요구를 `docs/04 §7` 에 적었다.
- **`published` 의 의미를 정정했다.** 처음에는 `PUBLISH` 의 수신자 수로 "구독 인스턴스가 있었는가"를 답하려 했는데, 모든 인스턴스가 패턴으로 구독하므로 **그 값이 항상 1 이상**이다(자기 자신 포함). 테스트가 그걸 잡았다. 이제 `published` 는 "팬아웃 채널로 발행했는지"이고 고객 도달도, 고객이 보고 있는지도 뜻하지 않는다.
- **연결 집계를 정수 카운터가 아니라 ZSET + Lua 로.** 정수 하나로는 "살아 있는 보유자"와 "죽은 보유자"를 구분할 수 없어, 인스턴스가 비정상 종료하면 그 배송이 **영구히 구독 불가**가 된다. 이 저장소는 `systemctl restart` + graceful shutdown 미설정이라 **배포마다 확정적으로** 일어난다. TTL 로도 해결되지 않는다(갱신하면 누수가 영구화, 갱신하지 않으면 정상 연결까지 날아간다). ZSET 은 stale score 를 걷어내는 것으로 스스로 낫고 `ZREM` 이 멱등이며, 오차가 **과소 집계(상한이 느슨해짐)** 쪽으로 떨어져 고객을 잠그지 않는다.
- **`GlobalExceptionHandler` 의 모든 응답에 Content-Type 을 명시했다**(전체 엔드포인트 영향). 지정하지 않으면 스프링이 `Accept` 로 컨텐트 협상을 하는데, 브라우저 `EventSource` 는 `Accept: text/event-stream` 만 보내므로 401·409·429 가 전부 **406** 으로 바뀌어 상태코드와 `ApiResponse` 본문을 둘 다 잃는다. 기존 엔드포인트는 모두 JSON 응답이라 동작이 바뀌지 않는다.
- **인터셉터를 `/api/customer/deliveries/**` 로 넓히지 않았다.** 현재 인증 없이 열려 있는 `POST .../quote`(요금 견적)가 조용히 401 이 되어 프론트가 깨진다. 인증을 거는 것 자체는 맞지만 이 이슈가 임의로 바꿀 범위가 아니다 — 그 API 를 구현하는 이슈에서 함께 등록한다.
- **투영(`TrackableDelivery`)으로 조회한다.** OSIV 가 꺼져 있고 연관이 LAZY 인데, SSE 는 emitter 콜백(요청 스레드도 아니고 트랜잭션도 없다)에서 값을 다시 읽으므로 엔터티를 올리면 `LazyInitializationException` 이 난다. 그 예외는 `GlobalExceptionHandler` 가 잡지 못해 `ApiResponse` 아닌 500 이 된다.
- **`left join` 이 필요하다는 계획의 근거가 틀렸다.** `RiderProfile` 이 `@MapsId` 로 PK 를 공유하므로 `o.assignedRider.memberId` 가 곧 `assigned_rider_id` FK 컬럼값이고, Hibernate 가 **조인 없이 그 컬럼만 읽는** SQL 을 만든다(실측). 명시적 `left join` 은 결과가 같으면서 `rider_profile` 을 실제로 조인해 손해다. 암묵적 경로로 되돌렸다.
- **정리 시 인터럽트 플래그를 걷어내고 복원한다.** Lettuce 는 `future.get()` 으로 기다려서 플래그가 서 있으면 즉시 `InterruptedException` 이 난다. 애플리케이션 종료 시 Tomcat 이 활성 SSE 요청을 강제 중단하며 그 상황을 만드는 것을 로그로 확인했다.
- **추적 가능 상태 집합을 `isTrackable()` switch 에서 파생시켰다.** 목록을 두 곳에 적으면 상태가 늘었을 때 한쪽만 고쳐지고, 결과는 "구독은 되는데 위치가 발행되지 않는" 배송이다.
- **`TrackingFixture` 를 공용으로 뺐다.** 배차 기능이 없어 픽스처로 상태를 만들어야 하는데, "진행 중 1건 UNIQUE 때문에 시나리오마다 회원을 새로 만들어야 한다"와 "상태는 전이 메서드로만 만들 수 있다"를 세 테스트가 각자 발견하게 두면 한 곳이 틀린다.

## 일부러 하지 않은 것

| 항목 | 이유 | 후속 |
|---|---|---|
| `GET .../tracking` 스냅샷 REST (`return null` 스텁) | Orval 대상 REST 계약이고, `init` 과 DTO 를 공유하지 않는다 — 겹치는 필드가 `status` 하나뿐이고 묶으면 `@Hidden` 스트림이 REST 스키마에 끌려다닌다 | #79 |
| 주문 완료·취소 시 **능동적** 연결 종료, `close` 이벤트 | 트리거가 상태 전이 트랜잭션이고 그 API 가 없다. order 채널이라 발행이 멈춰 "조용해지는" 것으로 누수는 막힌다 | #80 |
| heartbeat tick 에서 **세션 만료 재검증** | 노출 시간을 5분 → 15초로 줄일 수 있지만, 연결마다 세션 식별자를 JVM 에 들고 있어야 해 새 위험 표면이 생긴다. emitter 타임아웃 5분이 1차 상한이다 | 미등록(아래 미결) |
| `useTrackingStream` 프론트 훅 | 범위 밖. 단 이벤트 이름·`retry`·"스냅샷 먼저"·`measuredAt` 가드를 `docs/04 §7` 에 확정 기록해 추측하지 않게 했다 | #196 |
| 배차·완료 전이 API | 범위 밖. 테스트는 픽스처로 `ASSIGNED` 주문을 직접 만든다 | 별도 |
| `server.shutdown` 조정 | SSE 는 끝나지 않아 graceful 이 이득 없이 배포만 느리게 한다. 지금 손대면 배포 동작이 바뀐다 | 아래 미결 |
| 포괄 `Exception` 핸들러 | 전체 엔드포인트 영향. 이번엔 `DataAccessException` → 503 만 명시 처리 | 별도 이슈 후보 |

## 테스트

| 층 | 파일 | 검증한 것 |
|---|---|---|
| 단위 | `order/domain/OrderStatusTrackableTest` | 7개 상태 전부. 기대값을 DDL CASE 식에서 독립적으로 적어 구현을 그대로 호출하지 않는다 |
| 단위 | `location/sse/TrackingChannelTest` | 채널명 형식·왕복, 잘못된 채널명이 예외가 아니라 빈 결과 |
| 단위 | `location/sse/TrackingConnectionTest` | 완료된 emitter 에 보내면 `IllegalStateException` 이 아니라 false — 실제로 `complete()` 해서 실패 경로를 만든다 |
| 단위 | `location/sse/TrackingEmitterRegistryTest` | 주문별 인덱싱, 마지막 연결 제거 시 키 삭제(누수), 방어적 사본 |
| 단위 | `location/sse/InMemoryTrackingConnectionLimiterTest` | 한도·멱등 해제·stale 자기치유 |
| 단위 | `location/sse/TrackingHeartbeatSchedulerTest` | 죽은 연결 제거·자리 해제, **한 죽은 연결이 다른 연결의 heartbeat 를 막지 않음**, 생존 갱신(이미 stale 인 시각으로 등록해 되살아나는지) |
| 단위 | `location/service/RiderLocationServiceTest` (+8) | ACCEPTED 만 발행 / DUPLICATE·STALE 은 발행 안 함 / **발행이 예외를 던져도 200** |
| 통합 | `order/service/DeliveryTrackingAccessServiceIntegrationTest` | 소유권·상태별 404/409, **투영을 트랜잭션 밖에서 읽어도 예외 없음**, 배차 전 주문이 404 가 아니라 409 |
| 통합 | `location/sse/RedisTrackingConnectionLimiterIntegrationTest` | Lua 원자성(16스레드 동시 → 정확히 한도만큼), 멱등 해제, **stale 자기치유를 대기 없이 재현**, TTL 이 등록과 함께 걸림 |
| 통합 | `location/sse/RedisTrackingEventPublisherIntegrationTest` | 발행 채널·페이로드, 추적 가능 4개 상태 전부, **완료 후 발행 멈춤**, 배차 전·없는 라이더는 false |
| E2E | `location/sse/CustomerTrackingStreamE2ETest` | `init`(위치 있음/없음), **쿠키 없이 401**, `Accept: text/event-stream` 에서 406 이 되지 않음, 라이더 세션 401, 404·409·429, `retry` 필드, **`@Hidden` 이 실제로 스펙에서 제외했는지** |
| E2E | `location/sse/TrackingEventSubscriberE2ETest` | 수신 배선(직접 발행 → 스트림 도달), 페이로드 무변경 통과, 다른 주문 이벤트 미전달, 전달 불가 이벤트 뒤에도 계속 동작 |
| E2E | `location/sse/TrackingHeartbeatE2ETest` | 조용한 스트림 유지, **끊긴 클라이언트 자리 회수**, 끊긴 뒤 곧바로 재구독 |
| E2E | `location/sse/TrackingFanoutMultiInstanceE2ETest` | **A 에 연결 → B 에 POST → A 도달**, 레지스트리가 인스턴스별로 분리됨, 세션 공유 |

실행 결과:

```text
./gradlew test → BUILD SUCCESSFUL, 421 tests, 0 failures, 0 errors
  (작업 전 323개 → +98. Docker MySQL 8.4 + Redis 7.4.10, 로직 DB 1)
pnpm typecheck → 실행하지 않음 (프론트 변경 없음)
```

### 테스트가 회귀를 잡는지 다섯 번 확인했다

일부러 망가뜨려 실패를 보고 되돌렸다.

```text
① 연결 한도를 비원자적 구현(조회 → 세기 → 등록)으로
   → 동시에 몰려도 정확히 한도만큼만 성공한다 FAILED  (expected 3, but was 16)

② heartbeat 를 no-op 으로
   → 조용한 스트림에도 주석 프레임이 계속 흐른다 FAILED
     클라이언트가 끊으면 heartbeat 가 감지해 연결 자리를 해제한다 FAILED
     끊긴 뒤 곧바로 다시 구독할 수 있다 FAILED

③ 패턴 구독 등록을 제거
   → 수신 배선 E2E 4개 전부 FAILED

④ 발행 호출을 제거
   → 발행 관련 단위·응답 테스트 4개 FAILED

⑤ 발행을 "이 인스턴스의 연결에만 직접 전달"로  ← 가장 중요한 확인
   → B 에 올린 위치가 A 에 연결된 고객 스트림으로 전달된다 FAILED
     A 에 올린 위치도 A 의 스트림으로 전달된다 통과
     그 밖의 모든 테스트 통과
```

⑤ 가 이 사이클의 핵심이다. **팬아웃을 통째로 빼도 2인스턴스 테스트 하나를 제외한 전부가 통과한다** — 사람이 그 테스트를 포함하라고 결정한 것이 옳았다는 실증이다.

### 구현 중에 테스트가 잡아낸 것 (계획이 틀렸던 지점)

- **`ZREMRANGEBYSCORE` 의 상한은 포함이다.** 그냥 쓰면 정확히 임계값인 기록까지 지워, heartbeat 가 딱 임계값에 도착한 살아 있는 연결이 밀려난다. 인메모리 대체(`isBefore` = 배타)와 경계가 갈렸고 테스트가 잡았다. `(` 배타 경계로 고쳤다.
- **`SpringApplicationBuilder.properties()` 는 `application.yml` 을 덮지 못한다**(defaultProperties 소스라 우선순위가 낮다). 두 번째 인스턴스가 자동설정 제외를 되돌리지 못해 `EntityManagerFactory` 없이 떠서 기동이 깨졌다. 커맨드라인 인자로 넘겨야 한다.
- **"끊기면 자리가 해제된다"가 커밋 4 에서 실패했다.** 원인은 인터럽트가 아니라 "SSE 는 쓰기를 시도할 때만 끊김을 알 수 있다"는 것이었다. heartbeat 가 있어야 성립하므로 커밋 5 로 옮겼다.
- **`published` 를 수신자 수로 계산할 수 없다**(위 「스스로 판단한 것」).

### 검증하지 못한 것

- **CloudFront 를 통한 실제 스트리밍.** 설정값(타임아웃 상한·HTTP 버전)은 확인받았지만 **버퍼링 여부는 실측하지 않았다.** 이게 남은 위험 중 가장 크다 — 버퍼링이 걸리면 "로컬에서는 완벽하고 배포에서만 안 되는" 실패가 되고, 최악의 경우 API 오리진 분리가 필요해져 `SameSite=Lax` 쿠키 정책(`common/auth/SessionCookie`)과 그 E2E 까지 되돌아간다. **배포 후 `curl -N` 으로 CloudFront 도메인에 붙어 도착 간격을 먼저 확인해야 한다.**
- **진짜 프로세스 분리.** 2인스턴스 테스트는 같은 JVM 의 두 컨텍스트다. `static` 상태나 JVM 전역 싱글턴으로 인한 거짓 통과는 "레지스트리가 서로 다른 객체" 단언으로 부분적으로만 막힌다.
- **롤링 배포 중 구·신 버전 공존.** Pub/Sub 페이로드 형식이 **세 번째 배포 호환성 표면**이다(Flyway, Redis 값 형식에 이어). 두 버전을 동시에 띄우는 테스트는 불가능하고, "필드 추가만 허용, 제거·의미 변경 금지"를 javadoc 에 못 박는 것으로만 방어한다.
- **`kill -9` 후 연결 집계 상태**, **half-open TCP(모바일 네트워크 전환)**, **Pub/Sub 순서 역전**, **실제 TTL 만료**.
- **느린 소비자의 영향.** 디스패치 풀 4개와 heartbeat 스케줄러 2개가 블로킹 쓰기에 묶이는 상황은 재현하지 않았다.
- **브라우저 동작.** 프론트가 아직 이 스트림을 소비하지 않는다(#196). `EventSource` 의 재연결·오류 노출 동작은 스펙과 문서에 근거했을 뿐 이 저장소에서 실행해 보지 않았다.
- **CI.** `deploy.yml` 이 `-x test` 라 CI 에 테스트 단계 자체가 없다.

## 새로 생긴 미결 사항

1. **`server.shutdown` 이 설정 없이 graceful 로 동작한다**(테스트 로그에서 `Graceful shutdown aborted with one or more requests still active` 확인). SSE 는 끝나지 않으므로 **종료마다 대기 시간을 소진한 뒤 강제 중단**된다 — 배포가 그만큼 느려진다. 명시적으로 `immediate` 로 둘지 결정이 필요하다.
2. **CloudFront 버퍼링 미확인** (위 「검증하지 못한 것」). 배포 후 최우선 확인 항목.
3. **heartbeat tick 의 세션 만료 재검증을 넣을지.** 지금은 세션이 만료·로그아웃된 뒤에도 emitter 타임아웃(최대 5분)까지 위치가 흐른다.
4. **emitter 레지스트리가 테스트 사이에 살아남는다.** 인메모리 싱글턴이라 `IntegrationTestSupport`(MySQL·Redis 만 비운다)로 정리되지 않아, 앞 테스트의 연결이 남아 실제로 한 테스트를 실패시켰다. 내부 상태를 단언하지 않는 것으로 우회했지만 세 번째 클리너를 둘지 판단이 필요하다.
5. **Redis Pub/Sub 은 로직 DB 로 격리되지 않는다**(채널은 `SELECT` 를 무시한다). 테스트는 DB 1, 개발용 앱은 DB 0 인데 **채널은 공유된다.** `DatabaseCleaner` 의 TRUNCATE 가 AUTO_INCREMENT 를 리셋해 테스트 주문이 매번 낮은 id 를 받으므로, 개발용 앱을 띄운 채 테스트를 돌리면 채널명이 겹칠 수 있다(#82 에서 물린 것과 같은 계열). 단언을 좌표까지 정확히 맞춰 완화했지만, 채널 접두어를 프로파일로 분리할지 미결.
6. **ElastiCache 클러스터 모드를 켜면 이 구현이 동작하지 않는다.** cluster mode enabled 에서는 Redis 7 의 sharded pub/sub(`SSUBSCRIBE`)이 필요한데 `RedisMessageListenerContainer` 는 일반 pub/sub 만 다루고, 특히 `PSUBSCRIBE` 는 클러스터에서 문제가 된다. **클러스터 모드 비활성을 전제로 설계했다** — 배포 구성 확정 시 확인해야 한다.
7. **디스패치 풀 4개 때문에 같은 배송의 이벤트 순서가 뒤집힐 수 있다.** 프론트의 `measuredAt` 가드가 유일한 방어이고, 그 가드는 #196 이 구현한다 — 구현 전까지 지도 마커가 뒤로 튈 수 있다.
