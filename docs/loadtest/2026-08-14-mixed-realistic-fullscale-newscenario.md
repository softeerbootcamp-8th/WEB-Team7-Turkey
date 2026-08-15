## 목적

시나리오를 실사용에 더 가깝게 확장한 뒤 실제 규모(VU 2100)에서 정상 동작을 검증한다.

## 시나리오 변경 (`mixed-realistic.js`, `seed-loadtest-mixed.sql`)

- **AVAILABLE 라이더**: 300~500명 사이를 오가던 걸 **500명**으로 고정, 응답을 받으면 **sleep 없이
  바로 재요청**(닫힌 모델로 전환 — 콜 목록 부하를 의도적으로 키우는 실험 조건).
- **BUSY 라이더**: 시드가 주문을 `DELIVERING`이 아니라 **`ASSIGNED`**로 시작하도록 바꾸고, k6가
  무작위 간격(15~35초)으로 `START_MOVING_TO_PICKUP → PICK_UP → START_DELIVERING → complete
  (AUTH_CODE)` 4단계를 직접 호출해 테스트가 끝나기 전에 배송을 완료시킨다. 완료되면 서버가
  라이더를 AVAILABLE로 풀어주므로 이후 위치 전송은 멈춘다.
- 계정 규모: BUSY 700→**800**, AVAILABLE 400→**500** (총 VU 2×800+500=**2,100**).

## 진행하며 찾은 결함(전부 이번 확장 전엔 안 드러났던 것들)

`lt_r/lt_c`(BUSY 쌍)는 그동안 `/location`만 썼는데, 이번에 `/transition`·`/complete`·`current`를
처음 쓰면서 잠재돼 있던 시드 결함이 한꺼번에 드러났다:

1. **`lt_c` 주문에 운임 스냅샷이 없었다** — `order_fare_snapshot` INSERT 범위가 `lt_w`만이었다.
   `/transition`·`/complete`가 이를 조회해 400(`IllegalStateException`)으로 죽었다. 범위를
   `lt_c`까지 넓혔다.
2. **완료 정산에 라이더 쪽 `point_wallet`이 필요**한데 `lt_r`엔 없었다 — 추가했다.
3. **정리(DELETE) 순서 누락** — `rider_settlement`·`delivery_proof`가 완료 시 새로 생기는데
   `delivery_order`·`order_fare_snapshot`보다 먼저 안 지워 **재시드 자체가 FK 위반으로 깨졌다**.
   순서를 바로잡았다(거래내역 → 정산 → 인증 → 스냅샷 → 주문 → 지갑/프로필 → 회원).
4. **`point_transaction` 정리가 `delivery_order_id` 경유 조인이라 라이더 SETTLEMENT 거래
   (그 컬럼이 NULL)를 못 잡았다** — `member_id` 직접 조인으로 바꿨다.

**측정 도구 자체의 결함 2건도 발견**:

5. k6가 `/transition`·`/complete`·`/requests`를 URL(배송ID·좌표 포함)별로 별도 시계열로
   만들어, 배송당 한 번뿐인 호출의 우연한 실패 1건이 그 시계열만 "100% 실패"로 잡히는 문제 —
   `tags.name`을 고정해 시계열을 엔드포인트 단위로 합쳤다.
6. **`collect.py`의 실패율 쿼리 자체가 구조적으로 틀려 있었다**(`max(k6_http_req_failed_rate)`는
   상태코드별로 이미 쪼개진 시리즈에 `max()`를 거는 꼴이라, 표본이 적은 시리즈의 우연한 실패
   1건이 "전체 실패율 100%"로 보인다). 분자·분모를 각각 `sum()`한 뒤 나누는 진짜 비율로 고쳤다
   — 수정 후 k6 자체 집계(0.02%)와 일치한다.

## 결과 (G1GC, Hikari=10, VU=2100)

**k6 자체 집계(정본)**:

| 항목 | 값 |
|---|---|
| testid | `mixed-realistic-fullscale-newscenario-20260814-173656` |
| checks | 100% 성공(1,281,958건) |
| http_req_failed | **0.02%**(165/678,610) — threshold(`rate<0.01`... 참고: 이 임계값은 1% 기준이라 통과) 통과 |
| p95 | 330.08 ms |
| p99 | 629.28 ms |
| avg(성공 응답) | 146.26 ms |
| max | 2m15s(완료 전까지 열려있는 SSE 연결의 정상적인 전체 수명 — 오류 아님) |
| SSE 자연 종료 | 800개 중 **781개**(강제 종료 아님 — 완료 시 SSE를 닫는 `#450`이 대규모로 정상 발동) |

**앱 리소스(1단, steady-state)**:

| 항목 | 값 |
|---|---|
| 총 요청 / 평균 처리량 | 162,805건 / 3,618 req/s |
| 서버측 평균 처리시간 | 161.84 ms |
| Hikari 활성 최대 / 대기 최대 | 10 / **656** |
| GC 횟수 / 정지 합계 / 정지 비율 | 189 / 2.37초 / **5.3%** |
| 힙 사용 최대 / 상한 | 404 / 512 MiB |
| MySQL 실행 스레드 최대 | 6 |

**이전(구식 시나리오, VU=2000, Hikari=10) 대비**: GC 정지 비율이 1.1~1.5% → **5.3%**로,
Hikari 대기 최대가 0~65 → **656**으로 뚜렷이 올라갔다 — 상태 전이·완료·닫힌 루프 콜 목록이
실제로 유의미한 부하를 추가했다는 뜻이다. 이 규모가 Hikari·GC 비교에 쓸 만한 첫 조건으로 보인다.

## 참고: JIT 웜업의 한계

1단 JIT 컴파일 부하가 152.4 ms/s로 다소 높게 남았다. `location`은 초당 수백 번씩 불려 금방
데워지지만 `transition`/`complete`는 라이더당 딱 4번뿐이라, 웜업을 아무리 늘려도 이 특정
코드 경로가 JIT C2 컴파일 임계치(호출 1만 회)에 못 미칠 수 있다 — 웜업 부족이 아니라 저빈도
API가 갖는 구조적 한계로 보인다.

## 원본 데이터

- 측정: `docs/loadtest/2026-08-14-mixed-realistic-fullscale-newscenario-20260814-173656-raw.json`
- 웜업(참고용, BUSY_COUNT 700으로 잘못 실행돼 규모가 안 맞음): `warmup-fullscale-newscenario-20260814-171753`
