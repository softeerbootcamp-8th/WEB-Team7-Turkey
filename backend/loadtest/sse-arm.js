// #259 부하테스트 — SSE arm.
// GET /api/customer/deliveries/{deliveryId}/tracking/stream 을 구독해 두고, 서버가 위치를
// push하는 순간까지 걸리는 시간(지연)과 N을 늘릴 때 연결 유지 자체가 서버 자원(힙·fd)에 주는
// 부담(용량)을 잰다. Polling arm(polling-arm.js)과 같은 조건(N, 좌표, 시딩)에서 돌려야 비교가
// 유효하다.
//
// ⚠ 이 스크립트는 표준 k6 바이너리로 못 돈다 — k6는 SSE(EventSource)를 기본 지원하지 않는다.
// xk6-sse 확장을 넣어 커스텀 바이너리를 빌드해야 한다(#259 작업 중 확인, 2026-08-11):
//
//   go install go.k6.io/xk6/cmd/xk6@latest
//   xk6 build --with github.com/vladislav-chentsov/xk6-sse@latest
//
// phymbert/xk6-sse(원조, k6 x/sse 자동 해석 문서가 가리키는 그 확장)는 아직 k6 v2(go.k6.io/k6/v2)와
// 안 맞는다 — go.mod가 v1.6.1을 물고 있어 빌드는 되지만 "conflicting k6 versions" 경고와 함께
// 확장이 실제로 안 붙는다(런타임에 "unknown dependency: k6/x/sse"로 재현 확인). 대신
// vladislav-chentsov 포크(go.k6.io/k6/v2 사용)로 빌드해야 동작한다. 빌드된 바이너리는
// backend/loadtest/bin/k6.exe(gitignore 대상, 로컬 산출물이라 커밋하지 않음).
//
// 실행:
//   cd backend/loadtest
//   ./bin/k6.exe run -e N=10 sse-arm.js
//
// 환경변수는 polling-arm.js와 동일(N, DURATION, RIDER_INTERVAL, BASE_URL).
// RIDER_INTERVAL 기본값·근거는 polling-arm.js 상단 주석 참고 — 안드로이드 클라이언트(#391)가
// 고정 주기가 아니라 "20m 이동 OR 정지 120초"로 보내서, 보통값(2s)·물리적 최솟값(0.5s) 둘 다 잰다.

import sse from 'k6/x/sse';
import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { seedPairs } from './local/seed.js';
import { loginPairs } from './remote/ec2-login.js';

// polling-arm.js와 같은 규칙 — RUN_ID가 있으면 EC2 모드(remote/ec2-seed.sh 선행 필요).
const EC2_MODE = Boolean(__ENV.RUN_ID);

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const N = Number(__ENV.N || 10);
const DURATION = __ENV.DURATION || '2m';
const RIDER_INTERVAL_SEC = Number(__ENV.RIDER_INTERVAL || 2);

// 폴링 arm의 polling_latency_ms와 같은 정의로 잰다 — "위치가 실제로 바뀐 시점(measuredAt) ~
// 고객이 그 이벤트를 받은 시점"까지의 시간. SSE는 구조상 이 값이 곧 "요청→응답 시간"과 거의
// 같다(변경되면 즉시 push하므로) — 그래도 같은 지표명으로 재는 이유는 폴링과 나란히 비교하기
// 위해서다.
const sseLatencyMs = new Trend('sse_latency_ms', true);
const sseEventsReceived = new Counter('sse_events_received');

export const options = {
  scenarios: {
    riders: {
      executor: 'constant-vus',
      vus: N,
      duration: DURATION,
      exec: 'riderLoop',
    },
    customers: {
      executor: 'per-vu-iterations',
      vus: N,
      iterations: 1,
      maxDuration: DURATION,
      exec: 'customerSubscribe',
      startTime: `${RIDER_INTERVAL_SEC}s`,
    },
  },
  setupTimeout: '180s',
};

export function setup() {
  return EC2_MODE ? loginPairs() : seedPairs(N);
}

export function riderLoop(data) {
  const pair = data.pairs[(__VU - 1) % data.pairs.length];
  http.post(
    `${BASE_URL}/api/rider/location`,
    JSON.stringify({
      latitude: 37.5006 + Math.random() * 0.001,
      longitude: 127.0366 + Math.random() * 0.001,
      measuredAt: new Date().toISOString(),
    }),
    { headers: { 'Content-Type': 'application/json', Cookie: `SESSION_ID=${pair.riderSession}` } }
  );
  sleep(RIDER_INTERVAL_SEC);
}

// sse.open()은 콜백 안에서 이벤트 기반으로 동작하고, 연결이 열려 있는 동안 자바스크립트 메인
// 루프를 그대로 블로킹한다(확장 설계 문서 명시) — 그래서 폴링처럼 sleep으로 반복 호출하지 않고,
// VU당 정확히 한 번만 열어서 params.timeout(=DURATION)만큼 유지한다.
export function customerSubscribe(data) {
  const pair = data.pairs[(__VU - 1) % data.pairs.length];
  const url = `${BASE_URL}/api/customer/deliveries/${pair.deliveryId}/tracking/stream`;
  const params = {
    headers: { Cookie: `SESSION_ID=${pair.customerSession}` },
    timeout: DURATION,
  };

  let lastMeasuredAt = null;

  sse.open(url, params, function (client) {
    client.on('event', function (event) {
      // 구독 시작 시 서버가 보내는 "connected" 코멘트도 빈 값으로 이 콜백을 태운다(실측 확인,
      // 2026-08-11) — data가 없으면 실제 위치 이벤트가 아니라 그냥 건너뛴다.
      if (!event.data) {
        return;
      }

      let payload;
      try {
        payload = JSON.parse(event.data);
      } catch (e) {
        return; // 파싱 실패 프레임은 무시(구독자는 페이로드를 신뢰하고 그대로 쓴다는 계약이지만,
        // 부하테스트 스크립트는 방어적으로 스킵한다).
      }

      // #398 이후 이 채널은 위치(LocationPayload)뿐 아니라 상태 전이(StatusChangedPayload)도
      // 같이 흘려보낸다 — type으로 구분해 위치 이벤트만 지연 측정 대상으로 삼는다.
      if (payload.type !== undefined && payload.type !== 'location') {
        return;
      }
      if (!payload.measuredAt || payload.measuredAt === lastMeasuredAt) {
        return;
      }

      lastMeasuredAt = payload.measuredAt;
      sseEventsReceived.add(1);
      sseLatencyMs.add(Date.now() - new Date(payload.measuredAt).getTime());
    });

    client.on('error', function () {
      // params.timeout에 걸려 연결이 끊기는 것도 여기로 온다 — 정상 종료 경로라 별도 처리 없음.
    });
  });
}
