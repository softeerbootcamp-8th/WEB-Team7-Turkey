# 배포 서버 대상 부하테스트

**이 디렉터리의 스크립트는 실제 배포 환경을 때린다.** 로컬 대상은 `../local/` 이다.

디렉터리를 나눈 이유는 하나다: 로컬 측정과 배포 측정이 **같은 명령처럼 보이면 안 된다.**
배포 대상은 팀 공용 환경이고, 되돌리기 어려운 부작용(운영 DB에 남는 계정·주문, 다른 사람의
작업 중단)이 따른다.

## 무엇이 어디에 있나

| 파일 | 위치 | 왜 |
|---|---|---|
| `ec2-seed.sh` · `ec2-login.js` | **여기** | 배포 DB에 SQL 직접 시드 + 매니페스트 기반 로그인. 배포 전용이다 |
| `seed.js` | `../local/` | 회원가입 API의 `debugCode` 가 **local 프로파일에서만** 채워져 배포에서는 애초에 못 쓴다 |
| `polling-arm.js` · `sse-arm.js` | `../`(공용) | 측정 로직이 양쪽 공통이고 대상은 `BASE_URL` 로만 갈린다. 여기로 복사하면 두 벌이 되어 로컬·배포 수치를 직접 비교할 수 없게 된다 |
| `cleanup-seed.sql` | `../`(공용) | 계정 접두어가 로컬·배포에서 같다 |
| `seed-fare-policy.sql` | `../`(공용) | 활성 요금 정책 시드. 로컬·배포 둘 다 필요하다(이 스크립트가 계정 시딩보다 먼저 실행한다) |
| `collect.py` | `../`(공용) | `PROM_URL` 로 어느 Prometheus를 읽을지 정한다(아래) |

`ec2-seed.sh` 는 매니페스트(`ec2-seed-<RUN_ID>.json`)를 **자기 디렉터리(여기)에** 쓰고,
`ec2-login.js` 의 `open('./ec2-seed-<RUN_ID>.json')` 도 **자기 모듈 기준**으로 해석된다(k6 동작,
실측 확인). 그래서 둘은 항상 같은 폴더에 있어야 한다 — 하나만 옮기면 매니페스트를 못 찾는다.

## 실행

```bash
cd backend/loadtest
./remote/ec2-seed.sh <N> <RUN_ID>          # 배포 DB에 계정·배송 생성 → remote/ec2-seed-<RUN_ID>.json
docker compose ... k6 run -e RUN_ID=<RUN_ID> -e BASE_URL=https://<배포주소> \
  -e N=<N> polling-arm.js                  # RUN_ID 가 있으면 arm 이 EC2 모드로 동작한다
```

`RUN_ID` 를 주면 arm 이 `loginPairs()`(매니페스트 기반)를, 안 주면 `seedPairs()`(로컬 API 시딩)를
쓴다. **대상 전환은 `BASE_URL`, 시딩 방식 전환은 `RUN_ID` 다.**

## 지켜야 할 것

1. **사전 공지.** 팀 공용 환경이다. 누군가 그 시간에 그 서버로 작업·시연 중이면 측정이 그 사람의
   일을 부순다.
2. **끝나면 정리.** `../cleanup-seed.sql` 을 반드시 실행한다. 이 시딩이 만든 두 접두어
   (`lt_cust_`·`lt_rider_`)만 지우도록 좁혀져 있고, 특정 회차만 지우려면 그 파일의 `@run` 에
   RUN_ID 를 넣는다. 매니페스트(`ec2-seed-*.json`)도 함께 지워 둘 것.
3. **부하 생성기 위치를 리포트에 적는다.** 개발 PC에서 인터넷을 건너 때리면 회선·CloudFront가
   앱보다 먼저 병목이 되어, 그 수치는 앱의 한계가 아니다. VPC 안에서 생성한 경우와 반드시
   구분해 기록한다.
4. **k6 결과를 배포 Prometheus로 remote-write 하지 않는다.** 그쪽 보존 정책이 `15일 또는 8GB 중
   먼저 걸리는 쪽`인데, k6 시리즈는 카디널리티가 높아(URL·check·status × testid, 런마다 새 testid)
   8GB에 닿으면 Prometheus가 **오래된 블록부터 지운다 — k6와 운영 지표를 구분하지 않는다.**
   운영 지표가 밀려 사라진다.

## 지표 수집

k6 결과는 **로컬** Prometheus로 보내고(부하 생성기가 로컬이면 그대로 가능), 서버측 지표는 배포
Prometheus에서 따로 읽어 합친다. 배포 쪽 9090은 루프백 바인딩이라 두 가지 방법이 있다:

```bash
# ① SSH 터널 (개발 PC 쪽 포트는 9099 규약과 겹치니 다른 포트를 쓴다)
ssh -N -L 9098:localhost:9090 <모니터링-EC2> &
PROM_URL=http://localhost:9098 ../collect.py <start> <end> [testid]

# ② 터널 없이 컨테이너 안에서
docker exec monitoring-ec2-prometheus-1 wget -qO- 'http://localhost:9090/api/v1/query?query=...'
```

**미결**: 한 리포트에 로컬 k6 지표와 배포 서버 지표를 합치는 방식을 아직 정하지 않았다.
지금은 `collect.py` 를 두 번(각각 `PROM_URL` 다르게) 돌려 표 두 개를 붙이는 방식이다.
