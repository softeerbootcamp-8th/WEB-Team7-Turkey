// #259 부하테스트 — Polling arm.
// GET /api/customer/deliveries/{deliveryId}/location 을 주기 T로 반복 호출해, 라이더 위치
// 변경을 고객이 얼마나 늦게 인지하는지(지연)와 N을 늘릴 때 서버 자원이 어떻게 반응하는지(용량)를 잰다.
//
// 실행 전: 로컬 백엔드를 local 프로파일로 띄워둔다.
//   ./gradlew bootRun --args='--spring.profiles.active=local'
//
// 실행:
//   cd backend/loadtest
//   k6 run -e N=10 polling-arm.js
//
// 환경변수:
//   N              동시 고객·라이더 쌍 수(#259 §3 공통 독립변인). 기본 10.
//   DURATION       계단 유지 시간. 기본 2m(§5 "최소 1~3분" 권장의 하한).
//   POLL_INTERVAL  고객 폴링 주기 T(초). 기본 5.
//   RIDER_INTERVAL 라이더 위치 전송 주기(초). 기본 5(BUSY 확정값, #81).
//   BASE_URL       기본 http://localhost:8080.
//
// 대상 전환(로컬→EC2)은 BASE_URL만 바꾸면 된다 — 앱 코드 변경이 필요 없다(#259 §5 확인 완료).

import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { seedPairs } from './seed.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const N = Number(__ENV.N || 10);
const DURATION = __ENV.DURATION || '2m';
const POLL_INTERVAL_SEC = Number(__ENV.POLL_INTERVAL || 5);
const RIDER_INTERVAL_SEC = Number(__ENV.RIDER_INTERVAL || 5);

// 지연(#259 §3-i): "실제 변경 시점 ~ 클라이언트가 그 변경을 인지하는 시점". 요청→응답 왕복이
// 아니라, 고객이 새 위치를 처음 본 시각에서 그 위치의 measuredAt을 뺀 값이어야 공정 비교가 된다
// (계획 문서의 t=-4.9/t=0.13 예시 참고). 같은 measuredAt을 두 번째로 봤을 때는 세지 않는다.
const pollingLatencyMs = new Trend('polling_latency_ms', true);
const staleReads = new Counter('polling_stale_reads'); // 위치가 아직 안 바뀐 상태로 읽힌 횟수(참고용)

export const options = {
  scenarios: {
    riders: {
      executor: 'constant-vus',
      vus: N,
      duration: DURATION,
      exec: 'riderLoop',
    },
    customers: {
      executor: 'constant-vus',
      vus: N,
      duration: DURATION,
      exec: 'customerPoll',
      // 라이더가 최소 한 번은 위치를 보낸 뒤에 폴링을 시작해야 첫 폴링부터 유효한 위치를 본다.
      startTime: `${RIDER_INTERVAL_SEC}s`,
    },
  },
  setupTimeout: '180s',
};

export function setup() {
  return seedPairs(N);
}

export function riderLoop(data) {
  const pair = data.pairs[(__VU - 1) % data.pairs.length];
  http.post(
    `${BASE_URL}/api/rider/location`,
    JSON.stringify({
      latitude: 37.5006 + Math.random() * 0.001, // 정지 판정을 피하려는 목적이 아니라, 서버가
      longitude: 127.0366 + Math.random() * 0.001, // "같은 값 반복"을 특별 취급하지 않는지 확인용 미세 변동.
      measuredAt: new Date().toISOString(),
    }),
    { headers: { 'Content-Type': 'application/json', Cookie: `SESSION_ID=${pair.riderSession}` } }
  );
  sleep(RIDER_INTERVAL_SEC);
}

// VU마다 "마지막으로 latency를 계산한 measuredAt"을 기억한다. k6는 VU 하나당 별도 JS 런타임이라
// 모듈 스코프 변수가 자연스럽게 VU-로컬 상태가 된다(다른 VU와 안 섞인다).
let lastMeasuredAt = null;

export function customerPoll(data) {
  const pair = data.pairs[(__VU - 1) % data.pairs.length];
  const res = http.get(`${BASE_URL}/api/customer/deliveries/${pair.deliveryId}/location`, {
    headers: { Cookie: `SESSION_ID=${pair.customerSession}` },
  });

  if (res.status === 200) {
    const location = res.json('data.location');
    if (location && location.measuredAt) {
      if (location.measuredAt === lastMeasuredAt) {
        staleReads.add(1);
      } else {
        lastMeasuredAt = location.measuredAt;
        const latency = Date.now() - new Date(location.measuredAt).getTime();
        pollingLatencyMs.add(latency);
      }
    }
  }

  sleep(POLL_INTERVAL_SEC);
}
