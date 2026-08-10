# #450 배송 완료·자동취소 시 SSE 연결 능동 종료

- 이슈: #450 (Part of #446)
- 브랜치: `feature/450-sse-active-close` (dev 기준)
- 작업일: 2026-08-10
- 범위: backend + frontend(한 줄)

## 무엇을 풀었나

`DELIVERING→COMPLETED` 전이는 **그 자체가 마지막 이벤트**다. 라이더가 완료를 누르면 그 배송에
위치가 더 오지 않으므로, 그 한 번의 STATUS 프레임을 놓친 고객(탭이 백그라운드였다가 돌아온 경우,
프레임 유실, 인스턴스 간 전달 실패)은 **다음 복구 기회가 없다.** 위치 프레임은 5초마다 다시 오지만
(level trigger) 상태 전이는 한 번뿐이다(edge trigger) — 복구가 level 채널에만 존재했다.

해법은 "메시지를 다시 보낸다"가 아니라 **클라이언트가 DB에 다시 물어보게 만드는 것**이다.
서버가 emitter 를 닫으면 브라우저가 자동 재연결하고, 서버가 그 재연결을 DB 기준으로 409 거부하면
`EventSource` 가 영구 종료(`CLOSED`)되며, 프론트가 그 신호로 REST 재조회를 건다.

```
complete() 커밋 → PUBLISH tracking:close:{id}
  → (모든 인스턴스) TrackingCloseSubscriber → 자기 SseRelay.closeAll
  → 브라우저 자동 재연결 → 서버 409 → onerror(readyState=CLOSED) → REST 재조회
```

**이 신호가 유실돼도 정합성은 깨지지 않는다.** emitter 절대 수명(5분)이 만료되면 똑같은 사슬이
성립하기 때문이다. 이 구현은 최악 5분을 **재연결 왕복(약 3초)** 으로 줄이는 최적화이지, 정합성의
유일한 근거가 아니다. 그래서 발행 실패를 삼켜도 된다.

## 결정과 근거

### 종료 신호를 별도 채널로 뺐다 (`tracking:close:{id}`)

`tracking:order:{id}:close` 처럼 데이터 채널 **아래**에 두면 안 된다 — Redis glob 의 `*` 는 콜론을
포함해 매칭하므로 기존 패턴 `tracking:order:*` 에 그대로 걸리고, `TrackingSubscriber` 가 종료 신호를
**데이터 프레임으로 브라우저에 흘려보낸다.** 접두어를 완전히 분리했고, `TrackingChannelTest` 에
"종료 채널이 데이터 패턴에 걸리지 않는다"를 회귀로 고정했다.

대안이었던 "STATUS 프레임에 종료 의미를 얹기"는 탈락 — `TrackingSubscriber` 의 확정 설계(페이로드를
파싱하지 않고 그대로 흘린다, CLAUDE.md)를 깨야 한다. 채널을 나누면 **채널명만 보면 되고** 그 규약이
유지된다.

### 팬아웃을 거친다 (`SseRelay` 직접 호출 아님)

완료 처리를 한 인스턴스가 고객 emitter 를 들고 있는 인스턴스가 아니다. 위치 발행과 같은 이유로
Pub/Sub 을 거쳐야 하고, 이 규칙을 어겨도 단일 인스턴스 테스트는 전부 통과한다 — 그래서 검증은
2인스턴스 E2E(`TrackingFanoutMultiInstanceE2ETest#closesConnectionOnOtherInstanceWhenCompleted`)가
맡는다.

### `complete()` 뒤에 레지스트리에서 명시적으로 제거한다

`SseEmitter.complete()` 의 완료 콜백(`onCompletion`)이 레지스트리에서 빼 주긴 하지만 **동기 실행
보장이 없다.** 그 사이 도착한 이벤트가 이미 닫힌 emitter 로 전송을 시도한다. `finally` 에서
직접 `registry.remove` 를 부른다(객체 동일성 비교라 이중 제거는 멱등).

### 발행을 `afterCommit` 으로 미룬다

`publishStatus` 와 같은 이유다. 커밋 전에 발행하면 알림을 받은 고객이 곧바로 재조회해도 아직 커밋
전이라 **옛 상태를 읽는다.** 트랜잭션 안 어디서 호출하든(맨 끝이어도) 메서드 반환 뒤에 커밋되므로
호출 위치로는 막을 수 없다.

### 수동 취소(고객)는 발행하지 않는다

취소를 누른 당사자가 그 화면에 있고, 성공 후 화면이 재조회하면 `isTrackable` 이 false 가 되어
훅의 기존 정리 effect 가 연결을 닫는다. 서버가 개입할 이유가 없다.
**자동 취소(`DeliveryTimeoutService.cancelAndRefund`)는 다르다** — 고객이 그 시점에 아무 행동도
하지 않았고 화면은 WAITING 인 채로 남아 있으므로, 여기는 발행한다.

### 프론트는 `readyState` 로만 구분한다

`onerror` 는 "재연결 시도 중"과 "영구 종료" **둘 다에서** 발화한다. `EventSource.CLOSED` 일 때만
재조회를 건다 — 단순 연결 끊김(CONNECTING)에 재조회를 걸면 지하철·엘리베이터에서 재조회가
쏟아진다. `EventSource` 는 상태코드도 본문도 스크립트에 노출하지 않으므로 아는 것은 "영구 실패했다"
뿐이고, 그래서 읽을 수 있는 채널(REST)로 다시 물어본다.

## 테스트

| 층 | 무엇을 고정했나 |
|---|---|
| `TrackingChannelTest` (+4) | 종료 채널이 데이터 패턴에 안 걸림, 상호 파서가 서로의 채널을 거부, 형식 오류 → 빈 결과 |
| `TrackingPublisherTest` (+3) | 별도 채널로 발행, 커밋 전 미발행, Redis 실패 삼킴 |
| `SseRelayTest` (+4) | `closeAll` 이 완료+정리, 다른 배송 미영향, 이미 완료된 emitter 에서도 정리, 연결 없으면 무동작 |
| `DeliveryTimeoutServiceTest` (+1) | 자동 취소 성공 시 종료 신호 발행 / 조건부 UPDATE 0행이면 미발행 |
| `TrackingFanoutMultiInstanceE2ETest` (+1) | **다른 인스턴스**에서 완료 처리해도 연결이 닫힘 |

실행 결과: 백엔드 `./gradlew test` BUILD SUCCESSFUL, 프론트 `pnpm typecheck` + `pnpm test`
24 파일 175 테스트 통과.

## 일부러 뺀 것

- **프론트 `onerror` 분기의 단위 테스트.** 이 저장소의 vitest 는 `environment: 'node'` 이고
  `@testing-library/react`·jsdom 이 없다. boolean 하나를 검증하려고 새 의존성과 훅 렌더링 패턴을
  들이는 것은 과하다고 판단했다. 실제 검증은 2인스턴스 E2E(서버가 닫는다) + SSE 표준
  (`CLOSED` = 브라우저가 재연결을 포기한 상태)에 의존한다. 훅 렌더링 테스트가 다른 이유로
  필요해지면 그때 이 분기도 같이 덮는다.
- **클라이언트 타이머(WAITING→CANCELED 구간)와 focus 재조회** — #444 범위이고 다른 사람이 한다.
- **종료 사유(완료/취소) 구분** — 구독자가 본문을 읽지 않으므로 지금은 필요 없다. 필요해지면
  본문을 채우면 되고 리스너는 안 고쳐도 된다.

## 새로 생긴 미결

- 종료 신호가 **연결이 실제로 있는 인스턴스를 모른 채 전 인스턴스에 브로드캐스트**된다. 데이터
  채널과 같은 구조라 추가 비용은 아니지만, 완료·자동취소는 위치보다 훨씬 드물어 실질적으로 무시할
  수 있다.
- 닫기 → 재연결 → 409 사슬은 **브라우저 재연결 지연(`retry: 3000`)** 에 걸린다. 즉 완료 인지까지
  약 3초가 걸린다. 즉시성이 필요해지면 프론트가 `onerror`(CONNECTING)에서도 조건부로 재조회하는
  방안이 있지만, 지금은 재조회 폭주 위험이 더 크다고 봤다.
- **재연결이 연결 자리를 재사용하지 않는 기존 문제(#79)와 겹친다** — 연결 수 제한이 지금은 없어
  실제로 물리지 않지만, 제한이 다시 생기면 "능동 종료가 만든 재연결"이 그 한도를 소모한다.
