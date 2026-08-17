---
name: loadtest
description: Turkey 저장소에서 k6 부하테스트를 목적만 듣고 끝까지 수행한다. 대상 API 선정 → 시나리오 작성 → 로컬 계측 스택 기동 → 시드 → 실행 → Prometheus 지표 수집 → 리포트(docs/loadtest/)까지 한 사이클. "위치 갱신 부하테스트 해줘", "콜 목록이 몇 VU까지 버티는지 보자", "이 API 성능 재줘", "포화점 찾아줘", "부하 걸어봐", "k6로 테스트", "성능 회귀 확인" 처럼 성능·부하·처리량·지연·k6 가 등장하면 이 스킬을 쓴다. 기능 구현(mvp-feature)이나 단순 단위테스트에는 쓰지 않는다.
---

# 부하테스트

사용자는 **목적만** 말한다("위치 갱신이 몇까지 버티나", "콜 목록 인덱스 효과 확인"). 대상 선정·
부하 패턴·임계값·시드·실행·리포트는 이 절차가 정한다. 사용자가 값을 지정하면 그것이 우선한다.

## 원칙 세 개

1. **초록불을 의심한다.** 실패 0건·p95 1ms 는 서버가 빠른 게 아니라 **요청이 아무 일도 안 했다는
   신호**일 수 있다. 대상마다 "이 요청이 실제로 일을 하게 만드는 상태 조건"이 있고, 그건 OpenAPI
   스펙에 없다 → `references/preconditions.md` 가 정본이다. **시나리오를 쓰기 전에 반드시 읽는다.**
2. **측정 경로는 한 docker 네트워크 안에서 끝낸다.** k6 → app → mysql/redis 가 호스트를 경유하면
   Docker VM 경계 지연이 요청 처리 시간에 더해진다. 과거에 이걸로 병목을 잘못 짚어 결론을
   철회한 이력이 있다(`backend/loadtest/README.md` 「측정 이력」). 관측(스크레이프·결과 전송)은
   경유해도 무해하다.
3. **설정을 비교하는 실험은 컨테이너를 재시작한 직후 콜드 JVM으로 재면 안 된다.** 코어가
   제한된 컨테이너(`cpuset` 2코어 등)에서는 JIT 컴파일러가 요청 처리 스레드와 같은 코어를
   놓고 경쟁한다 — 힙·GC·Hikari 풀 크기 등 **어떤 옵션을 바꾸든, 그 차이보다 콜드/웜 상태 차이가
   몇 배 더 크게** 나올 수 있다(#502 IHOP 스윕 실측: 같은 설정을 한 번 더 돌렸을 뿐인데 램프
   구간 p95 가 798ms → 3,173ms 로 벌어졌고, 원인은 JIT 컴파일 부하 6배 차이였다 — 이미
   데워진 컨테이너에서 잰 런과 막 재시작한 컨테이너에서 잰 런을 비교한 것이었다). 절차는
   「3. 실행」의 웜업 단계를 반드시 따른다.

## 1. 목적 → 대상·부하 확정

1. 아래 **전체 API 목록**에서 후보를 고른다. Swagger UI 에 보이는 것과 `@Hidden` 으로 숨은 것을
   모두 담았다(43개 = 문서화 42 + 숨김 1). 살아 있는 스펙으로 교차 확인하려면 — **앱 포트를
   하드코딩하지 말 것**, compose 가 `8080-8089` 범위로 매핑해 매번 다르다:
   ```bash
   cd backend && PORT=$(docker compose port app 8080 | cut -d: -f2)
   curl -s localhost:$PORT/v3/api-docs | python3 -c "import sys,json;[print(m.upper(),p) for p,o in json.load(sys.stdin)['paths'].items() for m in o]"
   ```
2. `references/preconditions.md` 에서 대상의 **필수 상태·함정·반복 가능성**을 확인한다.
   반복 부하에 부적합한 대상(배차 수락, 주문 생성 등)이면 그 사실을 먼저 알리고, 동시성 검증
   시나리오로 바꿀지 다른 대상으로 갈지 정한다.
3. 목적에서 부하 패턴·임계값을 유도한다(같은 문서의 「부하 패턴」).
4. **확정안을 3줄로 요약해 알리고 진행한다.** 대상·VU·성공 판정 기준. 질문은 하지 않는다 —
   단, 후보 엔드포인트가 둘 이상으로 갈리거나 대상이 반복 부하에 부적합할 때만 묻는다.

### 전체 API 목록

`🔒` = 세션 쿠키 필요(인터셉터 `addPathPatterns` 등록됨). 표시 없으면 비인증으로 열려 있다.
`숨김` = `@Hidden` 이라 Swagger UI·`/v3/api-docs` 에 안 나온다. `스텁` = 구현이 `return null`.

**공개 (11)**
| 메서드 | 경로 | 비고 |
|---|---|---|
| GET | `/api/health` | 헬스체크 |
| GET | `/api/login-ids/availability` | 아이디 중복 확인 |
| POST | `/api/phone-verifications` | SMS 모킹(로그만) — 잴 의미 없음 |
| POST | `/api/phone-verifications/confirm` | |
| POST | `/api/customer/login` · `/api/rider/login` | bcrypt — setup 에서만 부를 것 |
| POST | `/api/customer/signup` · `/api/rider/signup` | |
| POST | `/api/customer/logout` · `/api/rider/logout` | 인터셉터 미등록, 컨트롤러가 쿠키 직접 읽음 |
| POST | `/api/customer/deliveries/quote` | 인터셉터에서 명시적 exclude. **대조군으로 좋다** |

**고객 (14)**
| 메서드 | 경로 | 비고 |
|---|---|---|
| GET | 🔒 `/api/customer/session` | |
| GET | 🔒 `/api/customer/deliveries` | 목록 |
| POST | 🔒 `/api/customer/deliveries` | 진행 중 1건 제한 → 반복 부하 불가 |
| GET | 🔒 `/api/customer/deliveries/active` | |
| GET | 🔒 `/api/customer/deliveries/{deliveryId}` | |
| PATCH | 🔒 `/api/customer/deliveries/{deliveryId}/cancel` | 상태 소진형 |
| GET | 🔒 `/api/customer/deliveries/{deliveryId}/location` | 폴링 arm(#311) |
| GET | 🔒 `/api/customer/deliveries/{deliveryId}/tracking` | **OSRM 외부 호출 포함** |
| GET | 🔒 `/api/customer/deliveries/{deliveryId}/tracking/stream` | **숨김** · SSE — k6 로 재지 말 것 |
| GET | 🔒 `/api/customer/points` | |
| POST | 🔒 `/api/customer/points/charges` | 동시 재전송 409 |
| POST | 🔒 `/api/customer/points/charges/{pointChargeId}/cancel` | 상태 소진형 |
| POST | 🔒 `/api/customer/points/charges/{pointChargeId}/confirm` | 상태 소진형 |
| GET | 🔒 `/api/customer/points/transactions` | |

**라이더 (18)**
| 메서드 | 경로 | 비고 |
|---|---|---|
| GET | 🔒 `/api/rider/session` | |
| GET | 🔒 `/api/rider/requests` | 콜 목록. 좌표 유무로 쿼리가 갈린다(#367) |
| GET | 🔒 `/api/rider/requests/{deliveryId}` | 콜 상세 |
| POST | 🔒 `/api/rider/requests/{deliveryId}/accept` | **1주문 1성공** — 경쟁 검증용 |
| POST | 🔒 `/api/rider/requests/{deliveryId}/skip` | |
| GET | 🔒 `/api/rider/deliveries/current` | |
| POST | 🔒 `/api/rider/deliveries/{deliveryId}/transition` | 상태 소진형 |
| POST | 🔒 `/api/rider/deliveries/{deliveryId}/complete` | 상태 소진형(인증정보 필수) |
| POST | 🔒 `/api/rider/location` | **BUSY 전용.** 상시 쓰기 경로 — 기본 시나리오 |
| GET·PATCH | 🔒 `/api/rider/operating-status` | PATCH 는 상태 소진형 |
| GET | 🔒 `/api/rider/points` | |
| GET | 🔒 `/api/rider/points/settlements` | **스텁**(`return null`) — 잴 것 없음 |
| GET | 🔒 `/api/rider/points/transactions` | |
| GET | 🔒 `/api/rider/points/withdrawals` | **스텁**(`return null`) |
| POST | 🔒 `/api/rider/points/withdrawals` | 잔액 소진형 |
| GET | 🔒 `/api/rider/history` | |
| GET | 🔒 `/api/rider/history/{deliveryId}` | |

목록에 없는 경로는 존재하지 않는다. `🔒` 가 없는데 인증이 필요해 보이면 **인터셉터 등록이
빠진 것**이므로(Spring Security 미사용) 부하테스트가 아니라 버그로 보고한다.

## 2. 시나리오 작성

**같은 엔드포인트면 복제하지 말고 기존 시나리오에 파라미터를 더한다**(하위호환 유지). 실사용
세션이 계단 램프를 그렇게 붙였다 — `STEPS`/`HOLD_SECONDS`/`RAMP_SECONDS` 를 추가하고, 안 주면
기존 4단 램프가 그대로 돌아 **이전 런과 비교가 유지된다**. 복제하면 두 벌이 갈라져 한쪽만
고쳐진다. 새 엔드포인트일 때만 `backend/loadtest/local/rider-location-update.js` 를 복제한다.

그 파일이 지키는 규칙:

- **로그인은 `setup()` 에서 계정당 1회.** 반환한 세션 쿠키를 VU 가 나눠 쓴다. 측정 구간에 bcrypt 가
  섞이면 그게 지연을 지배한다(로그인 p95 63ms vs 위치 갱신 4.4ms).
- **VU 수는 시드 계정 수(라이더 + 고객)에 맞춘다.** 시드 스크립트는 각 역할을 `@n` 명씩 만들므로
  총 계정은 `2n` 이다(기본 `n=100` → 200). 즉 기본값에서 `MAX_VU=200` 이 계정 1개당 VU 1개다.
  역할 하나만 쓰는 시나리오는 그 역할의 계정 수가 실질 배분 단위다. **VU 가 계정보다 많으면**
  여러 VU 가 한 계정을 공유해 같은 행·같은 Redis 키만 두드리고 수치가 왜곡된다 — 스크립트가
  경고를 찍는다(그대로 유지할 것). 더 큰 VU 가 필요하면 **먼저 `@n` 을 올려 계정을 늘린다.**
- VU→계정 배분은 `(__VU - 1) % 계정수`.
- 요청에 `tags: { api: '<이름>' }` 을 달아 임계값을 그 태그로 건다.
- **`setupTimeout` 은 계정 수에 비례한다.** setup 은 계정마다 순차로 로그인하고 bcrypt 가
  계정당 수십 ms 라, **계정 수 × 약 1초** 로 잡는다(실측 1.76s/쌍). 계정 1000개면 `900s` 가
  필요하다 — 「VU 를 계정 수에 맞춘다」가 계정을 늘리게 만들므로 이 값을 같이 올려야 한다.
  `SETUP_TIMEOUT` 환경변수로 받게 두는 편이 낫다.
- **계단 램프는 한 단을 90초 이상 유지한다.** Prometheus 스크레이프가 15초라 그보다 짧으면
  단계별 서버 지표가 조용히 빈다(`references/preconditions.md` 「부하 패턴」).
- `summaryTrendStats` 에 `p(95)`, `p(99)` 를 포함한다(기본값에는 없다).

## 3. 실행

각 단계는 앞 단계가 이미 돼 있으면 건너뛴다.

```bash
# (1) 관측 스택. 세 번째 파일이 exporter 를 부하 대상으로 돌린다 — 빼면 MySQL·Redis 수치가
#     무관한 빈 컨테이너 값으로 채워진다(초록불 거짓말의 다른 형태).
cd infra/monitoring-ec2
docker compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.loadtest.yml up -d

# 스크레이프 대상이 6개(mysql·redis·spring-app·node-app·node-db·node-redis) 전부 up 인지 본다.
# `/-/ready` 만으로는 부족하다 — Prometheus 가 200 을 주면서 **대상이 0개**일 수 있다.
curl -s 'localhost:9099/api/v1/targets?state=any' | python3 -c "import sys,json; \
t=json.load(sys.stdin)['data']['activeTargets']; \
print(f'타깃 {len(t)}개,', [x['labels']['job'] for x in t if x['health']!='up'] or '전부 up')"

# 6개가 아니면 Prometheus 를 **재생성**한다(restart 로는 안 풀린다 — 아래 이유).
docker compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.loadtest.yml \
  up -d --force-recreate prometheus

# (2) 부하 대상. 코드가 바뀌었거나 브랜치를 옮겼으면 --build 를 반드시 준다 — 낡은 이미지로
#     재면 조용히 다른 코드를 측정한다.
cd ../../backend && docker compose --profile app up -d --build

# (3) 시드 — 먼저 기존 데이터셋을 확인한다. 아래 「시드 충돌」을 반드시 읽을 것.
docker compose exec -T mysql mysql -uturkey -plocal turkey -e "
  select case when login_id regexp '^lt_[rc]' then 'lt_r/lt_c (위치 갱신용)'
              when login_id regexp '^lt_[wa]' then 'lt_w/lt_a (콜 목록용)'
              else '기본시드' end 데이터셋, count(*) 계정수
  from member group by 1;"
# 필요한 데이터셋이 이미 있으면 시드를 건너뛴다. 없으면 해당 스크립트만 돌린다.
docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/seed-loadtest-riders.sql

# (4) **다른 부하테스트가 돌고 있지 않은지 확인한다.** 0 이 아니면 멈추고 사람에게 묻는다 —
#     로컬 스택도 공용일 수 있다(다른 세션·다른 사람). 이걸 안 보고 실행해 남의 계단 테스트를
#     망친 적이 있다. 관측 스택 재생성·앱 재빌드도 같은 이유로 먼저 확인해야 한다.
curl -s --get 'localhost:9099/api/v1/query' --data-urlencode 'query=sum(k6_vus)' \
  | python3 -c "import sys,json;r=json.load(sys.stdin)['data']['result'];print('활성 VU:', r[0]['value'][1] if r else 0)"

# (5) 컨테이너 CPU·메모리 샘플러를 **부하와 병행** 띄운다. Prometheus 에 없는 값이고,
#     병목이 앱 CPU 인지 DB 인지 가르는 결정적 근거다. 규약 경로에 쓰므로 collect.py 가 알아서 읽는다.
./loadtest/sample-stats.sh &

# (6) **설정을 비교하는 실험(힙·GC 옵션, Hikari 풀 크기 등)이면 웜업부터 한다** — 원칙 3.
#     컨테이너를 방금 새 설정으로 (재)기동했다면 반드시 이 단계를 거친다. 같은 시나리오를
#     실제 측정 VU 와 동일하게 최소 90초 돌리고 결과는 버린다(적은 VU 로 웜업하면 동시성
#     관련 코드 경로 — Hikari 대기열, SSE 레지스트리 등 — 가 안 데워진다).
docker compose run --rm -e BASE_URL=http://app:8080 -e DURATION=90s \
  k6 run --tag testid=warmup-$(date +%Y%m%d-%H%M%S) /scripts/local/<시나리오>.js
# 시드에 시간 제약이 있으면(WAITING 풀 만료 등, 「시드 충돌」 참고) 웜업이 그 창을 다 써버리므로
# 웜업 직후 **재시드**하고 바로 (7)로 넘어간다.

# (7) 실행. --tag testid= 는 필수다(Grafana 대시보드가 이걸로 런을 구분한다).
ID=<대상약칭>-$(date +%Y%m%d-%H%M%S)
docker compose run --rm -e BASE_URL=http://app:8080 -e RIDER_COUNT=200 -e MAX_VU=200 \
  k6 run --tag testid=$ID /scripts/local/<시나리오>.js
```

웜업이 충분했는지는 리포트의 **JIT 컴파일 부하** 행으로 확인한다(`collect.py` 가 찍는다) —
측정 구간 내내 0에 가까워야 "이 런은 웜업됐다"고 믿을 수 있다. 수백 ms/s 씩 나오면 아직
콜드 상태로 잰 것이니 웜업을 더 길게 하고 다시 잰다. **콜드 상태로 잰 런과 웜업된 런을 같은
비교표에 섞지 않는다** — 그 차이가 비교하려던 옵션 차이보다 훨씬 클 수 있다.

### 시드 충돌 — 다른 사람의 데이터셋을 지우기 쉽다

부하테스트 시드가 둘이고 **접두어가 겹친다**:

| 스크립트 | 만드는 것 | 지우는 범위 |
|---|---|---|
| `seed-loadtest-riders.sql` | `lt_r*`(BUSY 라이더) + `lt_c*`(고객) + 진행 중 배송 | **`lt\_%` 전체** ⚠️ |
| `seed-loadtest-call-list.sql` | `lt_w*`(고객+WAITING 주문) + `lt_a*`(AVAILABLE 라이더) | `lt_w%`·`lt_a%` 만 |

**`seed-loadtest-riders.sql` 은 `DELETE FROM member WHERE login_id LIKE 'lt\_%'` 라 콜 목록
데이터셋(`lt_w*`·`lt_a*`, 주문 수천 건)까지 함께 지운다.** 자기 접두어만 지우는 반대쪽과 달라서,
위치 갱신 테스트를 돌리는 것만으로 남의 콜 목록 데이터가 사라진다. 실제로 이 스킬을 만들면서
한 번 겪었다.

- 시드를 돌리기 **전에** 위 확인 쿼리를 돌린다. 지울 데이터셋이 있으면 **사람에게 먼저 묻는다.**
- `reset-and-seed-local.sql` 은 전체 TRUNCATE 다. 다른 데이터셋이 있으면 절대 자동으로 돌리지
  않는다 — 기본 시드(`c1~c6`, `rbusy1~3` 등)가 이미 있으면 필요 없다.
- 필요한 데이터셋이 이미 있으면 **시드를 아예 건너뛴다.**

- **브랜치를 바꿔 작업한 뒤에는 Prometheus 를 반드시 재생성한다.** `targets/`·`targets.local/` 은
  리포지토리에 있는 파일이고 Prometheus 가 그 **디렉터리를 bind mount** 한다. 브랜치 전환이 그
  디렉터리를 지우고 새로 만들면, bind mount 는 기동 시점 inode 를 붙잡고 있어 **파일이 복구된
  뒤에도 컨테이너는 삭제된 옛 inode 를 계속 본다.** 증상은 "스크레이프 대상이 통째로 없어짐"
  이고, 앱 actuator 는 정상 200 을 준다 — 설정 오류처럼 보이지만 마운트가 끊긴 것이다.
  `restart` 로는 안 풀리고 `up -d --force-recreate prometheus` 가 필요하다(실제로 겪었다).
  `collect.py` 의 "exporter 가 부하 대상을 보고 있는지 확인" 안내로는 이 상태를 못 짚는다.
- **`BASE_URL=http://app:8080`** 을 쓴다(docker 네트워크 내부 주소). 호스트 포트로 돌리지 말 것.
- 시드가 DB 를 전부 지운다. 사용자가 로컬에서 작업 중인 데이터가 있는지 모르겠으면 먼저 알린다.
- **백그라운드 실행·출력 필터링 모두 괜찮다.** 훅은 k6 출력을 파싱하지 않고 측정 구간을
  Prometheus 에서 도출한다. 단 백그라운드면 훅이 시작 시점에 붙으므로 표가 안 나온다 —
  끝난 뒤 `./loadtest/collect.py --latest --steps` 를 직접 부른다(훅이 그렇게 안내한다).
- **5xx 가 0 이어도 앱 로그의 예외·경고를 확인한다.** 조용히 실패하는 경로가 있다
  (예: 시드가 `order_fare_snapshot` 을 빠뜨려 배차 만료 스캐너가 계속 죽던 사례):
  `docker compose logs app --since 60m 2>&1 | grep -iE 'exception|error|warn' | sort | uniq -c`

## 4. 리포트

k6 가 끝나면 훅(`.claude/hooks/k6-report.py`)이 `collect.py --latest --steps` 를 돌려 지표 표를
컨텍스트로 올려 준다(백그라운드 실행이면 직접 부른다). **측정 구간을 인자로 넘기지 않는다** —
Prometheus 의 VU 타임라인에서 도출하므로 setup 이 자동으로 빠진다. 그 표를 그대로 쓰고
**해석을 붙여** 파일로 남긴다:

```
docs/loadtest/<YYYY-MM-DD>-<testid>.md
```

훅은 같은 구간의 **raw 시계열**도 `docs/loadtest/<날짜>-<testid>-raw.json` 에 저장한다(5초 간격).
그 안에는 손으로 고른 15개 계열과, **Grafana 대시보드 패널 쿼리 결과**(`panels` 키, 실측 138개)가
함께 들어 있다 — 화면에서 보는 값과 같은 쿼리다. 표가 구간을 한 숫자로 접으므로, "언제 꺾였나 /
어느 VU 에서 튀었나"는 이 파일로 판단한다.

담을 것:
- 목적, 대상, 부하 패턴, 임계값 통과 여부
- 지표 표(훅 출력)와 raw 파일 경로
- **런 단위 p95/p99 는 k6 터미널 summary 값을 쓴다.** Prometheus 의 퍼센타일은 flush 구간별
  값이라 시간축으로 평균낼 수 없다(표에도 「구간 최댓값·참고」로 적혀 있다).
- 병목 판정과 근거. CPU·Hikari 대기·GC 정지·요청당 왕복 수를 함께 보고 **하나만 보고 단정하지
  않는다** — 과거에 Hikari 대기만 보고 "커넥션 풀이 병목"이라고 결론 냈다가 CPU 가 원인이었음이
  드러나 철회한 이력이 있다.
- **계단 런이면 단계별 표가 판정의 본체다.** 창 하나로 접은 값은 "어느 VU 에서 꺾였나"를
  못 보여 준다. `--steps` 가 VU 타임라인에서 유지 구간을 찾아 열로 세운다.
- **컨테이너 CPU 배분을 함께 적는다**(앱/MySQL/Redis). 2코어 제한이면 200% 가 만석이고,
  이 배분이 없으면 "앱 CPU 가 천장"인지 "DB 가 천장"인지 구분할 수 없다. k6 컨테이너 CPU 도
  적어 부하 생성기가 측정을 흐리지 않았다는 근거로 삼는다.
- **설정을 비교하는 실험이면 JIT 컴파일 부하 값을 적어 웜업이 됐다는 근거로 남긴다**(원칙 3).
  콜드 상태로 잰 런이 섞여 있으면 그 사실을 리포트에 명시하고, 다른 설정과 직접 비교하지 않는다.

여러 런을 비교해 얻은 판단(포화점, 결론, 측정 이력)은 `backend/loadtest/README.md` 에 누적한다.
`docs/loadtest/` 는 런 하나의 원본 수치다.

## 사람에게 물어야 하는
때

- 후보 엔드포인트가 둘 이상이고 목적만으로 못 좁혀질 때
- 대상이 반복 부하에 부적합할 때(상태 소진형) → 동시성 검증으로 바꿀지
- 시드가 지울 로컬 데이터가 아까울 수 있을 때
- 측정 결과가 기존 결론(`loadtest/README.md`)과 어긋날 때 → 재측정 조건을 맞췄는지 함께 확인

## 하지 말 것

- 배포 EC2 의 Prometheus 로 k6 결과를 밀지 않는다. 보존 상한(8GB)에 닿으면 오래된 블록부터
  지워져 **운영 지표가 밀린다** — k6 와 운영 지표를 구분하지 않는다.
- **이 절차는 `backend/loadtest/local/` 만 다룬다.** 배포 서버 대상 측정은 별도 디렉터리
  (`backend/loadtest/remote/`)에 있고 가드가 따로 있다 — 사전 공지, 정리 의무, 부하 생성기 위치
  기록. 사용자가 배포 대상을 요청하면 `backend/loadtest/remote/README.md` 를 먼저 읽고, **로컬 절차를 그대로
  옮겨 쓰지 않는다.**
- 호스트 `bootRun` 으로 재지 않는다(위 원칙 2).
- `docker-compose.loadtest.yml` 을 `docker-compose.override.yml` 로 바꾸지 않는다(모니터링 EC2 에서
  자동으로 끼어들어 운영 exporter 가 엉뚱한 곳을 본다).
