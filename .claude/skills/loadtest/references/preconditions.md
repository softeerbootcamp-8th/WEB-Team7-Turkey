# 엔드포인트별 부하테스트 사전조건

OpenAPI 스펙(`/v3/api-docs`)은 경로·본문 스키마만 알려준다. **그 요청이 서버에서 실제로 일을
하게 만드는 상태 조건은 스펙에 없다.** 이 문서가 그 부분이다.

## 왜 이 문서가 필요한가 — 초록불 거짓말

`POST /api/rider/location` 을 **AVAILABLE** 라이더로 때리면 전부 **200** 이 온다. 그런데 서버는
아무 일도 하지 않는다 — 진행 중 배송이 없어 발행할 채널이 없고, 그래서 배송 조회조차 하지
않는다. k6 는 200 만 보고 "실패 0건, p95 1ms" 라는 리포트를 낸다. 측정한 것은 **인터셉터 통과
비용뿐**이다.

이런 종류의 함정은 상태 코드로 걸러지지 않는다. 시나리오를 쓰기 전에 이 표를 볼 것.

## 시드 계정 (`scripts/reset-and-seed-local.sql`, 전 계정 비밀번호 `aa`)

| 계정 | 상태 |
|---|---|
| `c1` / `c2` / `c3` / `c4` | 고객, 진행 중 주문이 각각 WAITING / ASSIGNED / MOVING_TO_PICKUP / DELIVERING |
| `c5`, `c6` | 고객, 진행 중 주문 없음 → **주문 생성 테스트에 쓸 수 있는 유일한 계정** |
| `rpending1~3` | 라이더 AVAILABLE(콜 대기) |
| `rbusy1~3` | 라이더 BUSY(진행 중 배송 있음). `rbusy3` 은 1건뿐 |
| `roffline1~3` | 라이더 UNAVAILABLE |

부하테스트용 데이터셋은 둘이고, **접두어가 겹쳐 서로를 지운다**(SKILL.md 「시드 충돌」 참고):

| 스크립트 | 만드는 것 | 쓰는 대상 | 지우는 범위 |
|---|---|---|---|
| `scripts/seed-loadtest-riders.sql` | `lt_r1..N`(BUSY 라이더) + `lt_c1..N`(고객) + 진행 중 배송 N건 | 위치 갱신, 추적 | **`lt\_%` 전체** ⚠️ |
| `scripts/seed-loadtest-call-list.sql` | `lt_w1..N`(고객 + WAITING 주문) + `lt_a1..N`(AVAILABLE 라이더) | 콜 목록 | `lt_w%`·`lt_a%` 만 |

둘 다 개수를 `SET @n`(또는 `@riders`/`@orders`)으로 바꾼다. 재귀 CTE 기본 상한이 1000 이라
그보다 많이 만들려면 같은 세션에서 `SET SESSION cte_max_recursion_depth = <값>` 을 먼저 실행한다.

**VU 수는 계정 수(라이더 + 고객)에 맞춘다.** 각 스크립트가 역할당 `n` 명을 만들므로 총 계정은
`2n` 이다 — 기본 `n=100` 이면 200 이 계정 1개당 VU 1개가 되는 값이다. VU 가 계정보다 많으면
여러 VU 가 한 계정을 공유해 같은 행·같은 Redis 키만 두드리고, 버퍼풀 지역성이 비현실적으로
유리해진다(실측으로 확인된 왜곡, `loadtest/README.md`). **VU 를 더 올리려면 계정을 먼저 늘린다.**

## 인증 (Spring Security 없음 — 인터셉터 수동 등록)

세션 쿠키 `SESSION_ID` 를 받아 `Cookie` 헤더로 보낸다. **로그인은 `setup()` 에서 계정당 1회만**
한다 — bcrypt 검증이 계정당 수십 ms 라 측정 구간에 섞이면 그게 지연을 지배한다(실측: 로그인
p95 63ms vs 위치 갱신 4.4ms, 14배).

인증이 걸린 경로(`RiderWebMvcConfig` · `CustomerWebMvcConfig` 의 `addPathPatterns`):
- 라이더: `/api/rider/session`, `/requests`, `/requests/**`, `/deliveries/**`, `/location`,
  `/operating-status`, `/points`, `/points/**`, `/history`, `/history/**`
- 고객: `/api/customer/session`, `/deliveries`, `/deliveries/*`, `/deliveries/*/cancel`,
  `/deliveries/*/tracking`, `/deliveries/*/tracking/stream`, `/deliveries/*/location`,
  `/points/**`
- **비로그인**: `/api/customer/deliveries/quote`(명시적 exclude), `/api/health`,
  `/api/login-ids/availability`, `/api/phone-verifications*`, 로그인·회원가입

목록에 없는 경로는 **인증 없이 열려 있다**. 401 이 안 나온다고 인증된 것이 아니다.

## 부하 대상별 사전조건

### 반복 부하에 적합 (같은 요청을 무한히 보낼 수 있다)

| 엔드포인트 | 필수 상태 | 함정 |
|---|---|---|
| `POST /api/rider/location` | 라이더 **BUSY** + 진행 중 배송 | 본문은 **좌표만**(`deliveryId` 넣지 말 것 — #317 에서 제거됐다). `measuredAt` 은 **현재 시각 ISO 문자열**이어야 한다: Redis 조건부 갱신(`saveIfNewer`)이 과거 시각을 버려 조용히 아무 일도 안 한다. 고정 좌표도 가능하지만 조금씩 움직이는 편이 현실적이다 |
| `GET /api/rider/requests` | 라이더 **AVAILABLE** + WAITING 주문 존재 | `latitude`/`longitude` 를 **주면** bounding box 인덱스를 타고, **안 주면 WAITING 전체를 훑는다**(#367). 둘은 완전히 다른 쿼리라 어느 쪽을 재는지 정해야 한다. keyset 페이지네이션이므로 커서 없이 첫 페이지만 반복 조회된다 |
| `GET /api/customer/deliveries/{id}/location` | 고객 소유 + 라이더가 위치를 보내는 중 | 폴링 arm(#311). 라이더가 위치를 안 보내면 Redis 키가 비어 빈 응답의 비용만 잰다. **SSE arm 과 비교하려면 위치 전송 부하를 동시에 걸어야 한다** |
| `GET /api/customer/deliveries/{id}/tracking` | 고객 소유 + 종료 아닌 상태 | **외부 HTTP(OSRM 라우팅)를 호출한다**(연결 300ms + 읽기 700ms, 캐시 없음). 부하를 걸면 앱이 아니라 라우팅 서버가 병목이 되고, 연속 3회 실패 시 백오프로 호출을 건너뛰어 **도중에 측정 대상이 바뀐다**. WAITING 은 라이더가 없어 라우팅을 아예 호출하지 않는다 |
| `POST /api/customer/deliveries/quote` | 없음(비로그인) | 순수 계산 경로라 **대조군으로 좋다** — DB·Redis 왕복이 거의 없어 프레임워크 바닥 비용을 잰다 |
| 조회계 (`/api/rider/history`, `/api/customer/deliveries`, `/points/transactions`) | 해당 역할 세션 | 데이터 양에 비례한다. 시드의 주문 수(40/12/0 등)를 확인하고, 빈 계정으로 재면 빈 결과 비용만 잰다 |

### 반복 부하에 부적합 (한 번 성공하면 상태가 소진된다)

| 엔드포인트 | 왜 |
|---|---|
| `POST /api/rider/requests/{id}/accept` | 성공하면 그 주문은 ASSIGNED 가 되고 라이더는 BUSY 가 된다. **1주문 1성공.** 동시성(경쟁) 검증용으로만 쓰고, 그때는 "주문 1건 + VU N" 으로 **1건 성공 / N-1 건 실패**를 확인한다 |
| `POST /api/customer/deliveries` | 고객은 진행 중 주문을 1건만 가질 수 있어 **2번째부터 전부 409**. `c5`/`c6` 만 첫 성공이 가능하다. 대량으로 재려면 고객 계정을 VU 수만큼 시드해야 한다. `estimatedFare` 가 서버 재계산값과 다르면 409(대조 전용, 결제액 아님) |
| `PATCH /api/rider/operating-status`, `POST .../transition`, `.../complete` | 상태 전이라 같은 계정에 두 번 못 보낸다. 왕복 비용을 보려면 전이 사이클을 도는 시나리오가 필요하다 |
| `POST /api/customer/points/charges` | 동시 재전송은 409 로 거부된다(#32). 순차 재전송은 기존 건을 돌려준다 |

### k6 로 재지 말 것

- **SSE (`/api/customer/deliveries/*/tracking/stream`)** — 연결 유지형이라 요청/초 모델과 안 맞고,
  k6 의 HTTP 모듈은 스트림을 소비하지 않는다. 연결 수·팬아웃을 봐야 한다면 위치 갱신(발행 측)에
  부하를 걸고 Grafana 에서 emitter·PUBLISH 지표를 본다.
- **`/api/phone-verifications`** — 외부 SMS 모킹(로그만)이라 잴 의미가 없고, Redis TTL 키만 쌓인다.
- **`GET /api/rider/points/settlements` · `GET /api/rider/points/withdrawals`** — 구현이 `return null`
  스텁이다. 200 이 오지만 서버는 아무 일도 하지 않는다(초록불 거짓말의 가장 순수한 형태).

## 부하 패턴

전부 `loadtest/local/rider-location-update.js` 의 `MAX_VU` 기반 램프(20s→30s→30s→10s)를 재사용한다.

**VU 상한을 미리 정하지 않는다.** 어느 API 든 "여기까지가 한계"라고 측정된 값이 없다 —
위치 갱신 하나만 재 봤을 뿐이고, 그 수치를 다른 엔드포인트에 옮겨 쓸 근거가 없다. 그래서
**시작값만 정하고 처리량이 꺾일 때까지 올린다.** 계정 수가 실질적인 배분 단위이므로, 더 큰 VU 가
필요하면 시드의 `@n` 을 먼저 올린다.

| 목적 | 시작값 | 진행 |
|---|---|---|
| 포화점 탐색(기본) | 계정 수와 같게(`2n`, 기본 200) | 처리량이 더 안 늘고 지연만 커지는 지점이 무릎이다. 무릎이 안 보이면 계정을 늘려 더 올린다 |
| 한계·안정성 확인 | 포화점의 몇 배 | 과부하는 에러가 아니라 **지연으로** 나타날 수 있다(위치 갱신 실측: 500 VU 에서 실패 0, p99 216ms). 어디서 실패로 바뀌는지 본다 |
| 회귀 비교(코드 변경 전후) | 이전 런과 **같은 값** | 값을 바꾸면 비교가 무의미하다. 이전 런의 `testid`·VU·계정 수를 리포트에서 확인할 것 |
| 지연 SLO 검증 | 예상 실사용 부하 | 위치 갱신 실사용은 배송 1건당 0.2 req/s(5초 주기)다. 실사용 환산값을 리포트에 함께 적는다 |

**측정 구간은 90초 이상으로 잡는다.** Prometheus 스크레이프가 15초라, 구간이 짧으면
`increase()`/`rate()` 창에 표본이 1~2개뿐이어서 서버측 지표(평균 처리시간·GC·요청당 왕복 수)가
**조용히 빈다.** `collect.py` 가 이 경우를 구분해 알려 주지만, 애초에 짧게 재지 않는 게 낫다.

임계값(`thresholds`)은 목적에 맞춰 정한다. 기본은 `http_req_failed: rate<0.01` 과 대상별 p95 상한.
**백그라운드 요청(위치 갱신)과 사용자 대면 요청(조회)의 p95 기준은 달라야 한다.**
