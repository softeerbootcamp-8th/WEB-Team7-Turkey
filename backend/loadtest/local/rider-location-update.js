// 라이더 위치 갱신(POST /api/rider/location) 부하 테스트.
//
// 이 API 를 고른 이유: BUSY 라이더가 5초마다 때리는 유일한 상시 쓰기 경로이고, 요청 1건이
// MySQL 3회(인터셉터의 member·rider_profile 조회 + 수행 중 배송 조회) + Redis 2~3회
// (최신 위치 Lua + PUBLISH)를 쓴다고 문서에만 적혀 있고 실측된 적이 없다(CLAUDE.md 미결 항목).
// 동시 배송 수에 비례해 늘어나므로 스케일 한계를 먼저 봐야 하는 곳이다.
//
// 준비: 시드 데이터(backend/scripts/reset-and-seed-local.sql)의 BUSY 라이더를 쓴다.
//   cd backend
//   docker compose up -d mysql redis
//   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/reset-and-seed-local.sql
//   SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
//
// 실행:
//   docker compose --profile loadtest run --rm k6 run /scripts/rider-location-update.js
// 결과를 모니터링 스택 Prometheus 로 보내려면(infra/monitoring-ec2 가 떠 있을 때):
//   K6_OUT=experimental-prometheus-rw \
//   K6_PROMETHEUS_RW_SERVER_URL=http://host.docker.internal:9090/api/v1/write \
//     docker compose --profile loadtest run --rm k6 run /scripts/rider-location-update.js
import http from 'k6/http';
import { check, fail } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const PASSWORD = __ENV.PASSWORD || 'aa';

// 계정 목록. BUSY 가 아니면 서버가 발행할 배송을 못 찾아 이 테스트의 의미가 없다.
//  - 기본값은 기본 시드의 BUSY 라이더 3명.
//  - RIDER_COUNT=100 을 주면 scripts/seed-loadtest-riders.sql 이 만든 lt_r1..lt_r100 을 쓴다.
//    VU 수보다 많게 잡아 VU 당 라이더가 1명씩 돌아가게 하는 것이 목적이다 — 계정을 공유하면
//    같은 행·같은 Redis 키만 두드려 캐시가 유리해지고 saveIfNewer 경쟁이 과장된다.
const RIDERS = __ENV.RIDER_COUNT
  ? Array.from({ length: Number(__ENV.RIDER_COUNT) }, (_, i) => `${__ENV.RIDER_PREFIX || 'lt_r'}${i + 1}`)
  : (__ENV.RIDERS || 'rbusy1,rbusy2,rbusy3').split(',');

// 최대 VU. RIDER_COUNT 보다 크게 잡으면 여러 VU 가 한 라이더를 공유해 수치가 왜곡된다
// (계정 수 = 의미 있는 VU 상한. README 참고).
const MAX_VU = Number(__ENV.MAX_VU || 60);

// 계단 램프. STEPS=n 을 주면 MAX_VU/n 간격으로 n 단을 만들고 각 단을 HOLD_SECONDS 만큼
// 유지한다(예: MAX_VU=1000 STEPS=10 → 100,200,…,1000). 기존 4단 램프와 달리 **유지 구간이
// 있어야** 단계별로 서버측 지표를 따로 뽑을 수 있다 — Prometheus 스크레이프가 15초라
// increase()/rate() 창에 표본을 채우려면 한 단이 90초 이상이어야 한다(preconditions.md).
// STEPS 를 주지 않으면 기존 램프 그대로라 이전 런과 비교가 유지된다.
const STEPS = Number(__ENV.STEPS || 0);
const HOLD = Number(__ENV.HOLD_SECONDS || 90);
const RAMP = Number(__ENV.RAMP_SECONDS || 10);

const staircase = () => {
  const stages = [];
  for (let i = 1; i <= STEPS; i += 1) {
    const target = Math.max(1, Math.round((MAX_VU * i) / STEPS));
    stages.push({ duration: `${RAMP}s`, target });
    stages.push({ duration: `${HOLD}s`, target });
  }
  stages.push({ duration: '10s', target: 0 });
  return stages;
};

export const options = {
  // 닫힌 모델로 VU 를 올려 포화점(지연이 꺾이는 지점)을 본다. MAX_VU 에 비례해 램프한다.
  stages: STEPS > 0 ? staircase() : [
    { duration: '20s', target: Math.max(1, Math.round(MAX_VU / 6)) },
    { duration: '30s', target: Math.max(1, Math.round(MAX_VU / 2)) },
    { duration: '30s', target: MAX_VU },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // 5초 주기 백그라운드 요청이라 사용자가 체감하지 않지만, 300ms 를 넘기면 서버가
    // 이 경로에 붙잡혀 있다는 신호다.
    'http_req_duration{api:location}': ['p(95)<300'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 계정 수만큼 로그인하고 들어간다. bcrypt 해시 검증이 계정당 수십 ms(순차 실행)라 100개면
  // 기본 60초에 걸린다. 1000개면 1분 넘게 걸리므로 계정 수에 맞춰 넉넉히 둔다.
  setupTimeout: __ENV.SETUP_TIMEOUT || '900s',
};

// 로그인은 계정당 1회만 하고, VU 는 그 세션 쿠키를 받아 쓴다. 측정 대상(위치 갱신)에 로그인
// 비용(bcrypt)이 섞이지 않게 하려는 것이다.
export function setup() {
  const sessions = RIDERS.map((loginId) => {
    const res = http.post(
      `${BASE}/api/rider/login`,
      JSON.stringify({ loginId, password: PASSWORD }),
      { headers: { 'Content-Type': 'application/json' } },
    );
    if (res.status !== 200) {
      fail(`로그인 실패 ${loginId}: ${res.status} ${res.body}`);
    }
    const jar = res.cookies['SESSION_ID'];
    if (!jar || jar.length === 0) {
      fail(`로그인 응답에 SESSION_ID 쿠키가 없음 (${loginId})`);
    }
    return jar[0].value;
  });
  // 계정 이름을 다 찍으면 500개일 때 로그가 화면을 덮는다. 개수와 양끝만 남긴다.
  console.log(`세션 ${sessions.length}개 확보 (${RIDERS[0]} … ${RIDERS[RIDERS.length - 1]}), 최대 VU ${MAX_VU}`);
  if (MAX_VU > RIDERS.length) {
    console.warn(`주의: MAX_VU(${MAX_VU}) > 계정 수(${RIDERS.length}) — VU 여러 개가 한 라이더를 공유해 수치가 왜곡된다`);
  }
  // 계단 램프의 단계별 구간을 나중에 초 단위로 잘라내려면 램프 시작 시각이 필요하다.
  // setup 은 계정 수에 따라 수십 초~수 분이라 런 시작 시각으로 역산할 수 없다.
  console.log(`RAMP_START_EPOCH=${Math.floor(Date.now() / 1000)}`);
  return { sessions };
}

export default function (data) {
  // VU 를 라이더에 고르게 배분한다. 라이더 수 = 동시 진행 배송 수 = Redis 채널 수.
  const session = data.sessions[(__VU - 1) % data.sessions.length];

  // 매번 조금씩 움직인다. 서버측 위치 필터는 없지만(#297) Redis 최신 위치 갱신이
  // measuredAt 기준 조건부(saveIfNewer)라 시각은 반드시 현재 시각이어야 한다.
  const jitter = (__ITER % 100) * 0.0002;
  const body = JSON.stringify({
    latitude: 37.5665 + jitter,
    longitude: 126.9780 + jitter,
    measuredAt: new Date().toISOString(),
    accuracyMeters: 10,
  });

  const res = http.post(`${BASE}/api/rider/location`, body, {
    headers: { 'Content-Type': 'application/json', Cookie: `SESSION_ID=${session}` },
    tags: { api: 'location' },
  });

  check(res, {
    '200': (r) => r.status === 200,
    // 401 이면 세션 문제, 409/400 이면 라이더가 BUSY 가 아니거나 본문 계약이 어긋난 것이다.
    '인증 실패 아님': (r) => r.status !== 401,
  });
}
