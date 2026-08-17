# AVAILABLE 700명 콜 목록(3초 새로고침) + 주문 생성 300건 + 이력 1만 건 확장 시나리오

## 목적

`#502` 힙·GC·Hikari 튜닝 결론(`-Xms=-Xmx=512m`, G1GC)을 배포(GitHub Actions/systemd)에도
반영하고, 그 값이 다음 두 조건에서도 유지되는지 확인한다.

1. **실사용 근사**: AVAILABLE 라이더 700명이 3초 간격으로 콜 목록(`GET /api/rider/requests`)을
   새로고침하는 동안, 고객 300명이 테스트 시작 시 동시에 주문 300건을 생성한다.
2. **DB 규모**: 인스턴스가 이미 오래 운영돼 `delivery_order`에 `COMPLETED` 이력이 대량으로
   쌓인 상태(고정 10,000건 추가)를 재현해, 테이블이 커진 상태에서도 같은 힙/GC 설정이
   버티는지 본다.

## 설정 변경

- **`.github/workflows/deploy.yml`**: systemd override의 `JAVA_TOOL_OPTIONS`을
  `-Xmx228m -XX:+UseG1GC` → **`-Xms512m -Xmx512m -XX:+UseG1GC`**로 변경(#502 최종 리포트
  결론 반영 — 실측에서 힙이 512MiB를 넘은 적이 없었다).
- **`backend/docker-compose.yml`**: `JAVA_HEAP_OPTS` 기본값을 `-Xmx228m` →
  **`-Xms512m -Xmx512m`**로 변경, 배포와 로컬이 같은 기본값을 쓰도록 맞췄다.
- **주의(사람 확인 필요)**: t4g.micro는 총 메모리 906MiB다. 힙을 228m→512m로 올리면
  비-힙(메타스페이스·스레드 스택·direct buffer 등) 여유가 종전보다 줄어든다 — 원래
  228m은 "단독 증설은 스와핑 위험"이라는 판단으로 ergonomic 기본값에 고정해 둔 값이었다.
  이번 변경은 사용자 지시로 실행했고, 배포 후 실제 메모리 지표(스와핑 여부)를 지켜봐야 한다.
- **빌드 파이프라인 자체(`backend-ci.yml`)는 로컬 `Dockerfile`과 이미 일치**한다(JDK 21
  temurin, `./gradlew ... build`). 이번 점검에서 추가로 고칠 discrepancy는 못 찾았다.

## 시나리오·시드 확장 (`backend/loadtest/local/mixed-realistic.js`, `scripts/seed-loadtest-mixed.sql`)

- `mixed-realistic.js`에 파라미터 추가(기존 BUSY/tracking 시나리오와 하위호환 유지):
  - `AVAILABLE_POLL_INTERVAL_SEC`(기본 0=닫힌 모델): 콜 목록 재요청 사이 sleep. `3`을 주면
    "화면을 3초마다 새로고침"하는 실사용 근사가 된다.
  - `CUSTOMER_ORDER_COUNT`(기본 0): 테스트 시작 시 계정당 1회 `POST .../quote` → `POST
    .../deliveries`를 호출하는 `orderCustomers` 시나리오를 추가한다(진행 중 주문 1건 제한이라
    반복 불가 — #37 대조 검증대로 견적값 그대로 재사용).
  - `BUSY_COUNT=0`이면 `busyRiders`/`trackingCustomers` 시나리오 자체를 만들지 않는다(k6는
    `vus:0` 시나리오를 거부한다).
- `seed-loadtest-mixed.sql`에 `lt_oc1..lt_oc{@order_customers}`(주문 생성 고객, 진행 중 주문
  없음 + 포인트 100만 지급) 및 `@completed_history_extra`(위 `waiting_ratio` 계산과 무관하게
  더 얹는 고정 COMPLETED 이력, request_key 접두어 `54...`로 기존 이력과 분리) 추가.
- 이번 실행 파라미터: `@busy=800`(시드만 되고 이번 시나리오는 안 씀) · `@available=700` ·
  `@waiting=500` · `@order_customers=300` · `@completed_history_extra=10000` → 데이터셋 총
  15,000건(+ 기본 시드 159건).

## 실행

- 앱: `docker compose --profile app up -d --build --wait` (재빌드로 새 힙 기본값 적용,
  `-Xms512m -Xmx512m -XX:+UseG1GC` 확인됨).
- 웜업: `BUSY_COUNT=0 AVAILABLE_COUNT=700 CUSTOMER_ORDER_COUNT=0 DURATION=90s`로 90초(닫힌
  모델, 결과 버림) — 컨테이너를 방금 재기동했으므로 원칙 3에 따라 필수. 웜업 직후
  `jvm_compilation_time_ms_total` rate가 30.8ms/s로 낮아진 것을 확인(콜드 상태 수백ms/s 대비).
  `CUSTOMER_ORDER_COUNT=0`으로 웜업한 이유: 주문 생성 고객은 계정당 1회만 호출 가능해 웜업에서
  써버리면 본 측정에서 전부 409가 난다.
- 본 측정: `BUSY_COUNT=0 AVAILABLE_COUNT=700 AVAILABLE_POLL_INTERVAL_SEC=3 CUSTOMER_ORDER_COUNT=300
  DURATION=180s`, `testid=mixed-callorder-fullscale-20260815-163946`.
- xk6-sse 커스텀 바이너리(`loadtest/bin/k6-linux-arm64`)로 `docker run`(표준 k6 이미지는
  스크립트의 `k6/x/sse` import 자체를 못 읽는다 — `busyRiders`를 0으로 꺼도 import는 여전히
  평가된다).

## 결과 (k6 터미널 summary, 런 단위 정본)

| 항목 | 값 |
|---|---|
| 총 체크 | 84,300건, 성공 99.99%(84,299/84,300) |
| `http_req_failed` | 0.00%(threshold `rate<0.01` 통과) |
| p95 / p99(전체 요청) | 131.02 / 861.43 ms |
| avg(전체 요청) | 39.3 ms |
| max | 1.59s |
| 주문 생성(`orderCustomers`) | 300/300 iteration 완료, **299건 성공 · 1건 실패**(아래 「관찰된 결함」) |
| 콜 목록(`availableRiders`) | 700 VU, 3초 간격, 실패 0건 |

**앱 리소스(Prometheus, 훅 표 — 1단/steady-state)**:

| 항목 | 값 |
|---|---|
| 서버측 평균 처리시간 | 13.91 ms |
| 5xx 응답 | 0건 |
| GC 횟수 / 정지 합계 / 정지 비율 | 32회 / 0.18초 / **0.1%** |
| JIT 컴파일 부하 | 6.6 ms/s(충분히 웜업됨) |
| 힙 사용 최대 / 상한 | **403 MiB / 512 MiB**(상한을 넘은 적 없음) |
| Hikari 활성/대기 최대 | 0 / 0(*) |
| Tomcat 현재 연결 최대 | 1,007 |
| MySQL 실행 스레드 최대 | 3 |
| MySQL 문장/요청 · Redis 커맨드/요청 | 7.57 / 2.01 |

(*) Hikari 활성/대기가 15초 스크레이프 전 구간에서 한 번도 0을 벗어나지 않았다 — 이 부하
수준(요청당 처리 13.9ms, 커넥션 보유 시간이 스크레이프 간격보다 훨씬 짧음)에서는 순간
포착 확률 자체가 낮아서 나온 값으로 보인다(연결 자체가 없었다는 뜻은 아니다). Hikari가
병목이라는 신호는 어차피 없다.

## 해석

- **512MiB 고정 힙이 이 규모(700 VU 콜 목록 + 300건 동시 주문 생성 + 테이블 15,000행)에서
  전혀 압박받지 않았다** — 힙 사용 최대 403MiB(상한의 79%), GC 정지 비율 0.1%로 이전
  `#502` 최종 리포트의 무거운 시나리오(VU 2,100, 정지 비율 4.7~10.1%)보다 훨씬 가볍다.
  즉 이번 시나리오는 앱 CPU·힙 어느 쪽으로도 포화점 근처가 아니다(참고용 관찰이지
  포화점 재확인은 아니다).
- **완료 이력 10,000건 추가가 콜 목록·주문 생성 지연에 눈에 띄는 영향을 주지 않았다** —
  콜 목록은 `WAITING` 상태만 조회하는 인덱스(`idx_delivery_waiting_location`)를 타므로 이력
  건수 자체는 그 인덱스 크기에 들어가지 않는다(WAITING이 아닌 행은 인덱스 조건에서 걸러짐).
  테이블 총 행 수 증가가 버퍼풀·전체 스캔 계열 쿼리에 주는 영향은 이번 시나리오엔 없었다는
  뜻이지, 모든 쿼리에 무해하다는 뜻은 아니다.
- **배포 힙을 512m로 올리는 결정은 이 실험이 아니라 이전 `#502` 최종 리포트의 결론을
  가져다 쓴 것**이다 — 이번 실행은 그 값이 새 시나리오에서도 부작용이 없음을 재확인했을
  뿐, 512m가 맞다는 것 자체를 이번에 새로 증명한 것은 아니다.

## 관찰된 결함(원인 미규명, 이번 튜닝 결론에는 영향 없음)

주문 생성 300건 중 1건이 `uk_delivery_active_customer`(진행 중 주문 1건 제한) 위반으로
실패했다(`Duplicate entry '3085'`, 즉 `lt_oc1`). 성공한 299건은 customer_id가 전부
달랐고(중복 없음), 실패한 요청이 어느 VU에서 왔는지 request_key로 역추적했으나 그 VU
(263번)가 계산상 가리켜야 할 고객(`lt_oc263`, member 3347)은 주문이 아예 없다 — 즉 두
요청이 같은 세션(`lt_oc1`)을 공유한 것으로 보이는데, k6의 VU 인덱스 산식
(`(__VU-1) % orderCustomerSessions.length`)으로는 설명이 안 된다. 요청 43,600건 중 1건
(0.002%)이고 **서버의 동시성 제약 자체는 정확히 의도대로 거부했다**(중복 없이 딱 1건만
막음) — 원인은 k6 멀티 시나리오 VU 넘버링의 엣지 케이스로 추정되나 이번 조사 범위를
벗어나 미규명으로 남긴다. 재현되면 `orderCustomers` 세션 매핑을 `__VU` 대신 다른 식별자로
바꾸는 걸 검토할 것.

## 원본 데이터

- `docs/loadtest/2026-08-15-mixed-callorder-fullscale-20260815-163946-raw.json`(5초 간격
  시계열 + Grafana 패널 쿼리 143개)
- 웜업(참고용, 결과 버림): `warmup-callorder-*`(Prometheus에 안 실림 — `docker run`으로 돌려
  `K6_OUT` 미설정 상태였음, 본 측정부터는 `K6_OUT=experimental-prometheus-rw` 명시)
