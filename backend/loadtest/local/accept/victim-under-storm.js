// 피해자-under-폭주 (factorial 버전): 폭주를 constant-arrival-rate 로 만들어 "rate"를 독립 제어한다.
//   storm  = accept 를 STORM_RATE req/s 로 STORM_DUR 동안  |  victim = GET /api/rider/session 20/s
//   A(dose-response): B 고정 + STORM_RATE 스윕   |   B(격리): STORM_RATE 고정 + B 스윕
//   NOSTORM=1 → 폭주 없이 피해자 baseline.
import http from 'k6/http';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import { Trend, Counter } from 'k6/metrics';
import { accept, BASE_URL, SESSION_COOKIE } from './lib/common.js';

const NOSTORM = !!__ENV.NOSTORM;
const STORM_RATE = Number(__ENV.STORM_RATE || 200);
const STORM_DUR = __ENV.STORM_DUR || '30s';
const storm = new SharedArray('storm', () => JSON.parse(open(__ENV.PAIRS_FILE || './seed/pairs.json')));
const victim = new SharedArray('victim', () => JSON.parse(open('./seed/victim-pairs.json')));
const VSID = victim[0].sessionId;

const victimMs = new Trend('victim_ms', true);
const victimErr = new Counter('victim_err');

const scenarios = {
  victim: {
    executor: 'constant-arrival-rate',
    rate: 20, timeUnit: '1s', duration: STORM_DUR,
    preAllocatedVUs: 30, maxVUs: 100, exec: 'victimFn', startTime: '0s',
  },
};
if (!NOSTORM) {
  scenarios.storm = {
    executor: 'constant-arrival-rate',
    rate: STORM_RATE, timeUnit: '1s', duration: STORM_DUR,
    preAllocatedVUs: 300, maxVUs: 600, exec: 'stormFn', startTime: '0s',
  };
}

export const options = { discardResponseBodies: true, scenarios };

export function stormFn() {
  const i = exec.scenario.iterationInTest;
  if (i >= storm.length) return; // 시드 소진 방어
  accept(storm[i].sessionId, storm[i].orderId);
}

export function victimFn() {
  const res = http.get(`${BASE_URL}/api/rider/session`, {
    headers: { Cookie: `${SESSION_COOKIE}=${VSID}` },
    tags: { kind: 'victim' },
  });
  if (res.status === 200) victimMs.add(res.timings.duration);
  else victimErr.add(1);
}
