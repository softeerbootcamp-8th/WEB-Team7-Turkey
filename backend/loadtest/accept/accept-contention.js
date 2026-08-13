// 시나리오 A — 경쟁(락 경합 축). README §2-A.
// pairs.json 은 같은 orderId 를 B개 행에 반복해 B:1 경쟁 구조를 인코딩한다(sessionId 만 다름).
// shared-iterations 로 K×B 반복을 한꺼번에 풀어, 같은 주문의 B개 요청이 시간상 겹치게 만든다.
// 검증: 주문당 승자 정확히 1명(accept_won === K, 이중 배차 0).
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import { accept, won, lost, errors } from './lib/common.js';

const pairs = new SharedArray('pairs', () => JSON.parse(open(__ENV.PAIRS_FILE || './seed/pairs.json')));

// 시드가 몇 개 주문을 담았는지 = 기대 승자 수 K. 시더가 pairs 의 고유 orderId 수로 맞춰준다.
const EXPECTED_WINS = new Set(pairs.map((p) => p.orderId)).size;

export const options = {
  discardResponseBodies: true,
  scenarios: {
    contention: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 100), // 동시 요청 폭. 올릴수록 herd 겹침이 심해진다.
      iterations: pairs.length,      // K×B — 모든 쌍을 정확히 한 번씩
      maxDuration: '2m',
    },
  },
  thresholds: {
    // A 의 정합성 게이트: 5xx·타임아웃 0. (승자 수 == K 는 teardown 에서 단언.)
    accept_error: [{ threshold: 'count<1', abortOnFail: true }],
    accept_won_ms: ['p(99)<3000'],
    accept_lost_ms: ['p(99)<3000'],
  },
};

export default function () {
  const i = exec.scenario.iterationInTest;
  if (i >= pairs.length) return;
  const p = pairs[i];
  accept(p.sessionId, p.orderId);
}

export function setup() {
  if (!pairs.length) {
    throw new Error('pairs.json 이 비었다. 먼저 경쟁용 시드를 생성하라(seed/README.md).');
  }
  return { expectedWins: EXPECTED_WINS };
}

export function teardown(data) {
  // 이중 배차 검사. k6 커스텀 카운터의 최종값은 여기서 직접 못 읽으므로(요약은 handleSummary 몫),
  // 승자 수 == K 는 handleSummary 또는 서버 DB 조회로 확정한다. 여기서는 기대치를 로그로 남긴다.
  console.log(`[contention] 기대 승자 수 K = ${data.expectedWins}. accept_won 이 이 값과 같아야 한다(이중 배차 0).`);
}

// accept_won === K 를 실행 종료 시 자동 판정. 다르면 종료코드를 실패로 만들어 CI/스크립트에서 잡히게 한다.
export function handleSummary(summary) {
  const wonCount = summary.metrics.accept_won ? summary.metrics.accept_won.values.count : 0;
  const lostCount = summary.metrics.accept_lost_409 ? summary.metrics.accept_lost_409.values.count : 0;
  const errCount = summary.metrics.accept_error ? summary.metrics.accept_error.values.count : 0;
  const ok = wonCount === EXPECTED_WINS && errCount === 0;
  const line = ok
    ? `✅ 정합성 OK — 승자 ${wonCount} == K(${EXPECTED_WINS}), 패자 ${lostCount}, 오류 ${errCount}`
    : `❌ 정합성 위반 — 승자 ${wonCount} (기대 K=${EXPECTED_WINS}), 패자 ${lostCount}, 오류 ${errCount}`;
  return {
    stdout: `\n${line}\n`,
    'loadtest/accept/contention-summary.json': JSON.stringify(summary, null, 2),
  };
}
