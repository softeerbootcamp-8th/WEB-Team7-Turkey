// 실사용 근사 혼합 부하 시나리오.
//
// 지금까지 쓴 arm(rider-location-update.js 등)은 sleep() 없는 닫힌 모델이라 "포화점"을 보는
// 스트레스 테스트였다 — VU 수가 곧 동시 접속자 수는 맞지만, 요청 속도는 서버 응답 속도에 좌우돼
// "실제 라이더가 일정 간격으로 보낸다"를 재현하지 못했다. 이 스크립트는 세 트래픽을 각자의
// 실제 간격으로 페이싱해(open model) 동시에 돌린다:
//
//   1) BUSY 라이더 N명   — POST /api/rider/location, 2.4초 간격
//   2) 그 라이더를 추적하는 고객 N명 — GET .../tracking/stream (SSE) 연결 유지
//   3) AVAILABLE 라이더 M명 — GET /api/rider/requests, 3초 간격
//
// ⚠ (2)는 표준 k6 바이너리로 못 돈다 — xk6-sse 커스텀 빌드가 필요하다(sse-arm.js 상단 참고).
//   backend/loadtest/bin/k6-linux-arm64 가 이미 빌드돼 있다(docker network 안에서 돌리기 위해
//   linux/arm64로 크로스컴파일함 — 호스트에서 그냈로 돌리면 macOS Docker VM 경계를 넘어 지연이
//   붙는다, README의 "측정 경로는 한 docker 네트워크 안" 원칙).
//
// 준비: scripts/seed-loadtest-mixed.sql 이 lt_r*(BUSY)/lt_c*(추적 고객)/lt_a*(AVAILABLE)/
// lt_w*(WAITING 풀)을 만든다. WAITING 풀은 배차 대기 자동 취소 스캐너(5분)의 대상이므로
// 시드 직후 곧바로 이 스크립트를 실행해야 한다.
//
//   cd backend
//   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/reset-and-seed-local.sql
//   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/seed-loadtest-mixed.sql
//   docker run --rm -v "$(pwd)/loadtest/bin/k6-linux-arm64:/usr/bin/k6:ro" \
//     -v "$(pwd)/loadtest:/scripts:ro" --network backend_default \
//     -e BASE_URL=http://app:8080 alpine:3.20 \
//     /usr/bin/k6 run --tag testid=mixed-$(date +%Y%m%d-%H%M%S) /scripts/local/mixed-realistic.js
//
// setup()은 로그인 1,700건을 http.batch로 병렬 처리한다(WAITING 풀의 5분 창을 순차 로그인으로
// 다 써버리면 안 된다 — 다른 arm의 순차 로그인 관행과 다른 이유가 이것이다).
import http from 'k6/http';
import sse from 'k6/x/sse';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://app:8080';
const PASSWORD = __ENV.PASSWORD || 'aa';
const BUSY_COUNT = Number(__ENV.BUSY_COUNT || 700);
const AVAILABLE_COUNT = Number(__ENV.AVAILABLE_COUNT || 300);
const DURATION = __ENV.DURATION || '150s';
const LOCATION_INTERVAL_SEC = Number(__ENV.LOCATION_INTERVAL_SEC || 2.4);
const CALLLIST_INTERVAL_SEC = Number(__ENV.CALLLIST_INTERVAL_SEC || 3);
const RADIUS = Number(__ENV.RADIUS_METERS || 3000);
const LOGIN_BATCH = Number(__ENV.LOGIN_BATCH || 50);

const sseEventsReceived = new Counter('sse_events_received');
const sseLatencyMs = new Trend('sse_latency_ms', true);

// 순차 로그인(계정당 수십~수백 ms)으로 1,700건을 처리하면 WAITING 풀의 5분 창을 다 써버릴 수
// 있다. http.batch 로 LOGIN_BATCH 명씩 동시에 로그인해 setup() 을 초 단위로 끝낸다.
function batchLogin(basePath, loginIds) {
  const sessions = {};
  for (let i = 0; i < loginIds.length; i += LOGIN_BATCH) {
    const chunk = loginIds.slice(i, i + LOGIN_BATCH);
    const responses = http.batch(chunk.map((loginId) => ({
      method: 'POST',
      url: `${BASE}${basePath}`,
      body: JSON.stringify({ loginId, password: PASSWORD }),
      params: { headers: { 'Content-Type': 'application/json' } },
    })));
    responses.forEach((res, idx) => {
      const loginId = chunk[idx];
      if (res.status !== 200) {
        throw new Error(`로그인 실패 ${loginId}: ${res.status} ${res.body}`);
      }
      const jar = res.cookies['SESSION_ID'];
      if (!jar || jar.length === 0) {
        throw new Error(`SESSION_ID 쿠키 없음 (${loginId})`);
      }
      sessions[loginId] = jar[0].value;
    });
  }
  return sessions;
}

// 고객이 실제 화면처럼 "내 진행 중 배송"을 스스로 조회해 deliveryId 를 알아낸다(하드코딩 금지 —
// #100 의 GET /api/customer/deliveries/active).
function batchGetActiveDeliveries(customerIds, sessions) {
  const deliveryIds = {};
  for (let i = 0; i < customerIds.length; i += LOGIN_BATCH) {
    const chunk = customerIds.slice(i, i + LOGIN_BATCH);
    const responses = http.batch(chunk.map((loginId) => ({
      method: 'GET',
      url: `${BASE}/api/customer/deliveries/active`,
      params: { headers: { Cookie: `SESSION_ID=${sessions[loginId]}` } },
    })));
    responses.forEach((res, idx) => {
      const loginId = chunk[idx];
      if (res.status !== 200) {
        throw new Error(`active 조회 실패 (${loginId}): ${res.status} ${res.body}`);
      }
      const deliveryId = res.json('data.deliveryId');
      if (!deliveryId) {
        throw new Error(`진행 중 배송이 없음 (${loginId}) — seed-loadtest-mixed.sql 을 다시 확인할 것`);
      }
      deliveryIds[loginId] = deliveryId;
    });
  }
  return deliveryIds;
}

export const options = {
  scenarios: {
    busyRiders: {
      executor: 'constant-vus',
      vus: BUSY_COUNT,
      duration: DURATION,
      exec: 'sendLocation',
    },
    trackingCustomers: {
      executor: 'per-vu-iterations',
      vus: BUSY_COUNT,
      iterations: 1,
      maxDuration: DURATION,
      startTime: '2s', // 라이더가 최소 한 번 위치를 보낸 뒤 구독을 시작한다(sse-arm.js와 동일 근거).
      exec: 'watchTracking',
    },
    availableRiders: {
      executor: 'constant-vus',
      vus: AVAILABLE_COUNT,
      duration: DURATION,
      exec: 'pollCallList',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  setupTimeout: __ENV.SETUP_TIMEOUT || '300s',
};

export function setup() {
  const riderIds = Array.from({ length: BUSY_COUNT }, (_, i) => `lt_r${i + 1}`);
  const customerIds = Array.from({ length: BUSY_COUNT }, (_, i) => `lt_c${i + 1}`);
  const availableIds = Array.from({ length: AVAILABLE_COUNT }, (_, i) => `lt_a${i + 1}`);

  const riderSessions = batchLogin('/api/rider/login', riderIds);
  const customerSessions = batchLogin('/api/customer/login', customerIds);
  const availableSessionMap = batchLogin('/api/rider/login', availableIds);
  const activeDeliveries = batchGetActiveDeliveries(customerIds, customerSessions);

  const busyPairs = riderIds.map((riderId, i) => {
    const customerId = customerIds[i];
    return {
      riderSession: riderSessions[riderId],
      customerSession: customerSessions[customerId],
      deliveryId: activeDeliveries[customerId],
    };
  });
  const availableSessions = availableIds.map((id) => availableSessionMap[id]);

  console.log(`설정 완료: BUSY 쌍 ${busyPairs.length}개, AVAILABLE 라이더 ${availableSessions.length}개`);
  return { busyPairs, availableSessions };
}

export function sendLocation(data) {
  const pair = data.busyPairs[(__VU - 1) % data.busyPairs.length];
  const jitter = (__ITER % 100) * 0.0002;
  http.post(`${BASE}/api/rider/location`, JSON.stringify({
    latitude: 37.5665 + jitter,
    longitude: 126.9780 + jitter,
    measuredAt: new Date().toISOString(),
    accuracyMeters: 10,
  }), {
    headers: { 'Content-Type': 'application/json', Cookie: `SESSION_ID=${pair.riderSession}` },
    tags: { api: 'location' },
  });
  sleep(LOCATION_INTERVAL_SEC);
}

export function watchTracking(data) {
  const pair = data.busyPairs[(__VU - 1) % data.busyPairs.length];
  const url = `${BASE}/api/customer/deliveries/${pair.deliveryId}/tracking/stream`;
  const params = {
    headers: { Cookie: `SESSION_ID=${pair.customerSession}` },
    timeout: DURATION,
  };
  let lastMeasuredAt = null;

  sse.open(url, params, function (client) {
    client.on('event', function (event) {
      if (!event.data) return; // "connected" 코멘트 등 빈 프레임은 건너뛴다.
      let payload;
      try {
        payload = JSON.parse(event.data);
      } catch (e) {
        return;
      }
      if (payload.type !== undefined && payload.type !== 'location') return;
      if (!payload.measuredAt || payload.measuredAt === lastMeasuredAt) return;
      lastMeasuredAt = payload.measuredAt;
      sseEventsReceived.add(1);
      sseLatencyMs.add(Date.now() - new Date(payload.measuredAt).getTime());
    });
    client.on('error', function () {
      // params.timeout 만료도 여기로 온다 — 정상 종료 경로.
    });
  });
}

export function pollCallList(data) {
  const session = data.availableSessions[(__VU - 1) % data.availableSessions.length];
  const lat = (37.44 + ((__VU * 37) % 250) * 0.001).toFixed(6);
  const lon = (126.81 + ((__VU * 53) % 360) * 0.001).toFixed(6);
  const res = http.get(
    `${BASE}/api/rider/requests?latitude=${lat}&longitude=${lon}&radiusMeters=${RADIUS}`,
    { headers: { Cookie: `SESSION_ID=${session}` }, tags: { api: 'requests' } },
  );
  check(res, {
    '200': (r) => r.status === 200,
    '인증·상태 실패 아님': (r) => r.status !== 401 && r.status !== 403,
  });
  sleep(CALLLIST_INTERVAL_SEC);
}
