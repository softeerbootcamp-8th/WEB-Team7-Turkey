## 목적

exp3(`docs/loadtest/2026-08-13-mixed-realistic-exp3-heap50-ihop30-20260813-112422.md`,
`...exp3repeat-....115318.md`)는 램프 구간(부하 시작 직후)에 지연 스파이크가 있었다
(p95 최댓값 1,525.75ms, Hikari 대기 최대 877). exp3의 힙 설정은 `-XX:MaxRAMPercentage=50`
(초기 힙은 컨테이너 기본 ergonomic 값 — 작게 시작해 부하 아래서 커진다)이라, 램프 구간에서
힙이 자라며 커밋·GC가 겹쳐 순간 정지를 만들었을 가능성을 의심했다.

이 실험은 **`-Xms` = `-Xmx` = 512m(고정 힙, 시작부터 최대 크기 커밋)**으로 바꿔 같은 스크립트로
재실행해, 램프 구간 스파이크가 힙 리사이징 때문이었는지 확인한다. IHOP(30%)은 exp3와 동일하게
유지해 힙 크기 조정 방식만 단일 변수로 바꿨다.

## 대상·부하 패턴

- 대상: `backend/loadtest/local/mixed-realistic.js` (exp1~3와 동일 스크립트, 파라미터 변경 없음)
  - BUSY 라이더 700명 — `POST /api/rider/location` 0.5초 간격
  - 그 라이더를 추적하는 고객 700명 — `GET .../tracking/stream` (SSE)
  - AVAILABLE 라이더 300명 — `GET /api/rider/requests` 3초 간격(콜 목록, 좌표 포함)
  - 세 시나리오 모두 `constant-vus`로 즉시 최대 VU(1,700)에서 시작(계단 램프 아님) — "램프 구간"은
    시작 직후 수십 초의 워밍업 구간을 가리킨다.
- JVM: `JAVA_HEAP_OPTS=-Xms512m -Xmx512m`, `JAVA_GC_OPTS=-XX:InitiatingHeapOccupancyPercent=30`,
  G1GC(고정), 컨테이너 `cpus: 2` / `cpuset: 0-1`. exp3 대비 바뀐 것은 힙 옵션뿐.
- 임계값: `http_req_failed rate < 0.01`

## 결과: 램프 구간 스파이크 재현 안 됨

시작 직후(t=0~75초) 5초 간격 시계열 비교(`k6_p95_seconds`, `hikari_pending`, `heap_used_bytes`):

| t+초 | VU | exp3 p95(ms) | exp3 Hikari대기 | exp3 힙(MiB) | exp4 p95(ms) | exp4 Hikari대기 | exp4 힙(MiB) |
|---|---|---|---|---|---|---|---|
| 10 | 1000→1700 | **1,525.8** | **877** | 242.5 (72→242, 성장 중) | **798.3** | **0** | 265.6 (이미 512 근처로 커밋됨) |
| 20 | 1700 | 1,296.6 | 877 | 242.5 | 620.0 | 0 | 265.6 |
| 40 | 1700 | 814.8 | 0 | 476.5 | 440.1 | 0 | 189.6 |
| 55 | 1700 | 789.0 | 35 | 222.7 | 305.8 | 0 | 250.5 |
| 75 | 1700 | 747.6 | 0 | 334.0 | 186.6 | 0 | 437.8 |

- **Hikari 대기 큐가 램프 구간 내내 0이다**(exp3는 t=10~20s 구간에 877까지 쌓였다). exp3에서
  본 "커넥션 풀 대기 폭주"가 사라졌다 — 힙이 시작부터 512MiB로 커밋돼 있어 급격한 리사이징이
  요청 처리를 막는 구간이 없기 때문으로 보인다.
- **피크 p95가 거의 절반**(1,525.8ms → 798.3ms)이고, 그 뒤로도 매 시점 exp3의 절반 안팎을
  유지하며 더 빨리 안정 구간(186ms, t=75s)에 도달한다(exp3는 같은 시점에 747.6ms로 아직 높다).
- GC 횟수·정지 비율 자체는 exp3와 비슷한 수준(아래 표)이라, **개선은 GC 총량이 아니라 램프
  구간의 리사이징·커밋 타이밍에서 왔다**는 가설과 맞는다.
- Hikari 대기 최대는 전체 구간에서는 288(단, 램프 구간이 아니라 t=130~140s 구간, 즉 정상 가동
  중 별개의 일시적 스파이크 — 원인 미조사, 램프 스파이크와는 무관).

## 지표 표 (Prometheus, testid=`mixed-realistic-exp4-fixedheap512-ihop30-20260813-160708`)

측정 구간: 170초(setup 제외)

| 항목 | 값 |
|---|---|
| **--- k6(부하 발생기) ---** | |
| 총 요청 | 223,666건 |
| 평균 처리량 | 1,316 req/s |
| 피크 처리량(15초 해상도) | 1,491 req/s |
| 실패율(구간 최댓값) | 0.00% |
| 최대 VU | 1,700 |
| 드롭된 iteration | 0건 |
| **--- 앱(Micrometer) ---** | |
| 서버측 평균 처리시간 | 7.65 ms |
| 5xx 응답 | 0건 |
| Hikari 활성 최대 | 10 |
| Hikari 대기 최대 | 288 (램프 구간 아님, 위 설명 참고) |
| JVM 플랫폼 스레드 최대 | 38 |
| Tomcat 현재 연결 최대 | 1,707 |
| GC 횟수 | 147회 |
| GC 정지 합계 | 1.05초 |
| GC 정지 비율 | 0.6% |
| 힙 사용 최대 | 442 MiB |
| 힙 상한 | 512 MiB |
| MySQL 실행 스레드 최대 | 4 |
| k6 비정상 응답 | 0건 |
| **--- 요청당 왕복 수 ---** | |
| MySQL 문장/요청 | 13.77 |
| Redis 커맨드/요청 | 4.79 |

**런 단위 p95/p99(k6 종료 시 터미널 summary, 정본)**: `http_req_duration` p95=33.61ms,
p99=205.26ms, max=831.86ms (avg=12.54ms). `checks_succeeded`=100%(30,000/30,000),
`http_req_failed`=0.00%(0/222,449), threshold `http_req_failed rate<0.01` **통과**.

### 단계별 지표 — 1단(유지 구간, 램프 제외)

| 항목 | 1700 VU |
|---|---|
| 총 요청 | 184,622건 |
| 평균 처리량 | 1,477 req/s |
| 서버측 평균 처리시간 | 6.76 ms |
| Hikari 대기 최대 | 288 |
| GC 정지 비율 | 0.6% |
| 힙 사용 최대 | 438 MiB |
| MySQL 문장/요청 | 13.37 |
| Redis 커맨드/요청 | 4.73 |

- raw 시계열: `docs/loadtest/2026-08-13-mixed-realistic-exp4-fixedheap512-ihop30-20260813-160708-raw.json`
- 컨테이너 CPU 샘플러(`sample-stats.sh`)가 이 런의 측정 구간 시작 직후(t+4초 지점)에 죽어
  이번 런은 `docker stats` 기반 컨테이너 CPU/메모리 표를 못 남겼다 — Prometheus 지표(위 표)로
  대체한다.

## 시딩 과정에서 발견한 결함(이 실험과 별개, 반드시 공유)

부하 시작 전 데이터 상태를 확인하다가 **`fare_policy`·`item_type_surcharge`·
`order_fare_snapshot` 세 테이블이 전부 비어 있는 상태**를 발견했다. 이 상태로 첫 실행(테스트id
`...exp4-fixedheap512-ihop30-20260813-155410`, 이 파일과 별개로 raw json만 남아 있다)을 돌렸더니
AVAILABLE 라이더의 콜 목록 폴링(`GET /api/rider/requests`)이 WAITING 주문을 하나라도 후보로
찾을 때마다 `400 배송요청에 예상 운임 스냅샷이 없습니다`로 실패했다 — 전체 220만 건 중
15,000건(콜 목록 폴링 전량)이 실패해 `http_req_failed` 임계값이 깨졌다(전체 비중 6.8%로 희석돼
보임). 5xx는 0건이라 Micrometer 기반 표만 보면 놓친다.

- **원인**: `seed-loadtest-mixed.sql`은 `reset-and-seed-local.sql`이 먼저 만들어 둔
  `fare_policy(policy_version='LOCAL-1.0')`·`item_type_surcharge`에 의존하는데, 이 로컬 DB에는
  그게 없는 상태였다(언제 비워졌는지는 특정 못 함 — 이 세션 시작 시점에 Docker/OrbStack 데몬이
  내려가 있었고 mysql·redis 컨테이너도 멈춰 있었다, 그 사이 발생한 것으로 추정).
  `docs/loadtest/`에 이미 있던 `...exp4-xms512-20260813-143204`,
  `...exp5-xms512-ihop30-20260813-143635-raw.json`(이번 세션 이전에 생성된 것으로 보임 — 이
  대화 맥락 밖의 실행)도 같은 결함 상태에서 나온 결과일 가능성이 있어 신뢰하지 않았다.
- **조치**: `fare_policy`에 `LOCAL-1.0`(reset-and-seed-local.sql과 동일 값: base_fare 5000,
  distance_unit 1000m당 1000원, 상한 30000m) 1건과 그 5개 품목 할증을 직접 INSERT한 뒤
  `seed-loadtest-mixed.sql`을 재실행해 500건의 `order_fare_snapshot`을 복구했다. 이후 재실행한
  것이 본 리포트의 testid(`...-160708`)다 — `checks_succeeded=100%`로 확인.
- **확인 필요**: `#502` 계열 실험(exp1~3repeat)의 원본 리포트는 Micrometer 표만 남겨 이 결함
  유무를 알 수 없다. 그때 이미 `fare_policy`가 있었는지(정상이었을 가능성이 높다 — k6 터미널
  summary를 직접 안 봤으므로 단정은 못 함) 재확인이 필요하면 알려달라.
- 이번 세션 로컬 DB에는 `mixed-realistic-exp4-xms512-20260813-143204`,
  `mixed-realistic-exp5-xms512-ihop30-20260813-143635`라는, 이 대화에서 실행하지 않은 testid의
  Prometheus 데이터도 남아 있었다(raw json 일부 존재, md 리포트 없음) — 이 대화 이전에 같은
  실험을 이미 시도한 흔적으로 보인다. 내용을 검토해 중복 여부를 판단할지는 별도로 확인이 필요하다.

## 결론

**exp3의 램프 구간 스파이크는 힙 리사이징 때문이라는 가설이 이 실험으로 뒷받침된다.**
`-Xms=-Xmx=512m`로 고정하자 램프 구간의 Hikari 대기 큐 폭주(877→0)와 피크 p95(1,525.8ms→798.3ms,
약 -48%)가 함께 사라졌다. GC 횟수·정지 비율은 두 실험이 비슷해, 개선은 GC 총량이 아니라 시작
시점의 리사이징·커밋 타이밍에서 온 것으로 보인다. 다음 힙·GC 튜닝 실험에서는 `-Xms=-Xmx` 고정을
기본값으로 가져가는 편이 낫다.
