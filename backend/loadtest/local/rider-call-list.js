// 라이더 콜 목록 조회(GET /api/rider/requests) 부하 테스트.
//
// 목적: 좌표를 주는 경로(#367 bounding box 인덱스 idx_delivery_waiting_location, FORCE INDEX)와
// 주지 않는 경로(WAITING 전체를 읽어 자바에서 거르고 정렬)의 비용 차이를 실측한다. 둘은 같은
// 엔드포인트지만 완전히 다른 쿼리라, 어느 쪽을 재는지 MODE 로 명시한다.
//
//   MODE=coords    (기본) latitude/longitude 를 실어 보낸다 → bounding box + 반경 필터
//   MODE=nocoords  좌표 없이 보낸다 → WAITING 전체 조회 후 자바 정렬(sort 는 REQUESTED_AT 로 대체)
//
// 준비:
//   cd backend
//   docker compose --profile app up -d --build
//   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/reset-and-seed-local.sql
//   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/seed-loadtest-call-list.sql
//   # 시드 후 3분 안에 실행할 것 — 만료 스캐너(#42)가 5분 지난 WAITING 을 취소한다.
//
// 실행:
//   ID=calllist-coords-$(date +%Y%m%d-%H%M%S)
//   docker compose run --rm -e BASE_URL=http://app:8080 -e MODE=coords -e RIDER_COUNT=100 \
//     k6 run --tag testid=$ID /scripts/rider-call-list.js
import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const PASSWORD = __ENV.PASSWORD || 'aa';
const MODE = __ENV.MODE || 'coords';
const RADIUS = Number(__ENV.RADIUS_METERS || 3000);

// 계정: AVAILABLE 라이더여야 한다. AVAILABLE 이 아니면 403 만 받고 조회 비용은 재지 못한다.
// scripts/seed-loadtest-call-list.sql 이 만드는 lt_a1..lt_aN 을 쓴다.
const RIDERS = __ENV.RIDER_COUNT
  ? Array.from({ length: Number(__ENV.RIDER_COUNT) }, (_, i) => `${__ENV.RIDER_PREFIX || 'lt_a'}${i + 1}`)
  : (__ENV.RIDERS || 'rpending1,rpending2,rpending3').split(',');

const MAX_VU = Number(__ENV.MAX_VU || 60);

// 응답 건수를 지표로 남긴다. 0 이 계속 나오면 "빠른 서버"가 아니라 "빈 목록의 비용"을 잰 것이다
// (시드가 만료됐거나 좌표가 주문 분포 밖). 리포트에서 반드시 확인할 값.
const itemsReturned = new Trend('items_returned');

export const options = {
  stages: [
    { duration: '20s', target: Math.max(1, Math.round(MAX_VU / 6)) },
    { duration: '30s', target: Math.max(1, Math.round(MAX_VU / 2)) },
    { duration: '30s', target: MAX_VU },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // 사용자가 화면에서 기다리는 조회다. 위치 갱신(백그라운드, 300ms)보다 엄격하게 본다.
    'http_req_duration{api:requests}': ['p(95)<500'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  setupTimeout: '180s',
};

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

  // 초록불 확인: 부하를 걸기 전에 한 번 조회해 실제로 목록이 나오는지 본다. 여기서 0건이면
  // 시드가 없거나 만료된 것이라, 그대로 돌리면 "아무것도 안 한 비용"을 재게 된다.
  const probe = http.get(`${BASE}${path(0)}`, { headers: { Cookie: `SESSION_ID=${sessions[0]}` } });
  if (probe.status !== 200) {
    fail(`사전 조회 실패: ${probe.status} ${probe.body}`);
  }
  const count = (probe.json('data.items') || []).length;
  console.log(`세션 ${sessions.length}개, MODE=${MODE}, 사전 조회 ${count}건 (hasNext=${probe.json('data.hasNext')})`);
  if (count === 0) {
    fail('사전 조회가 0건이다 — 시드를 다시 넣거나(만료 스캐너) 좌표 범위를 확인할 것');
  }
  if (MAX_VU > RIDERS.length) {
    console.warn(`주의: MAX_VU(${MAX_VU}) > 계정 수(${RIDERS.length}) — VU 여러 개가 한 라이더를 공유해 수치가 왜곡된다`);
  }
  return { sessions };
}

// VU 마다 다른 좌표를 쓴다. 한 점에 몰면 같은 인덱스 구간·같은 버퍼풀 페이지만 두드려
// 실제보다 유리해진다. 범위는 시드가 주문을 뿌린 격자(위도 37.430~37.700 / 경도 126.800~127.180)와 같다.
function riderPoint(vu) {
  return {
    latitude: (37.44 + ((vu * 37) % 250) * 0.001).toFixed(6),
    longitude: (126.81 + ((vu * 53) % 360) * 0.001).toFixed(6),
  };
}

function path(vu) {
  if (MODE === 'nocoords') {
    // 좌표를 빼면 서비스가 WAITING 전체를 읽고, sort=DISTANCE 는 REQUESTED_AT 으로 대체된다.
    return '/api/rider/requests';
  }
  const p = riderPoint(vu);
  return `/api/rider/requests?latitude=${p.latitude}&longitude=${p.longitude}&radiusMeters=${RADIUS}`;
}

export default function (data) {
  const session = data.sessions[(__VU - 1) % data.sessions.length];

  const res = http.get(`${BASE}${path(__VU)}`, {
    headers: { Cookie: `SESSION_ID=${session}` },
    tags: { api: 'requests' },
  });

  const ok = check(res, {
    '200': (r) => r.status === 200,
    // 403 이면 라이더가 AVAILABLE 이 아니다(시드 확인). 401 이면 세션 문제.
    '인증·상태 실패 아님': (r) => r.status !== 401 && r.status !== 403,
  });
  if (ok) {
    itemsReturned.add((res.json('data.items') || []).length);
  }
}
