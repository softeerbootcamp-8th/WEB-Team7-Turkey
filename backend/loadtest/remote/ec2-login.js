// EC2 부하테스트용 로그인 모듈 (#459) — ec2-seed.sh 가 SQL로 만들어 둔 계정들을 실제
// 로그인 API로 로그인시켜 세션을 받는다. seed.js(로컬, 회원가입까지 API로)와 반환 모양을
// 맞춰서, arm 스크립트(../polling-arm.js · ../sse-arm.js — 최상위 공용) 쪽 코드는 그대로 두고 setup() 안의
// 데이터 소스만 바뀌게 한다.
//
// 사용 순서:
//   1) ./ec2-seed.sh <N> <RUN_ID>            (SSH로 DB에 계정·배송 직접 INSERT)
//   2) k6 run -e BASE_URL=https://<cloudfront> -e RUN_ID=<RUN_ID> /scripts/polling-arm.js
//
// open()은 k6 init 컨텍스트에서만 허용돼 setup() 안에서 못 부른다 — 그래서 매니페스트
// 파일을 모듈 최상위(초기화 시점)에서 미리 읽어 둔다. RUN_ID가 없으면(로컬 모드) null.
const RUN_ID = __ENV.RUN_ID || null;
const MANIFEST_RAW = RUN_ID ? open(`./ec2-seed-${RUN_ID}.json`) : null;

import http from 'k6/http';

const PASSWORD = 'loadtest1234'; // ec2-seed.sh 가 심어둔 bcrypt 해시와 짝이 되는 평문.

export function loginPairs() {
  if (!MANIFEST_RAW) {
    throw new Error('[ec2-login] RUN_ID 가 없음 — ec2-seed.sh 로 먼저 시딩하고 -e RUN_ID=... 로 넘겨야 함');
  }
  const manifest = JSON.parse(MANIFEST_RAW);

  const pairs = manifest.pairs.map((p) => ({
    customerSession: login('customer', p.customerLoginId),
    riderSession: login('rider', p.riderLoginId),
    deliveryId: p.deliveryId,
  }));

  return { pairs, runId: manifest.runId };
}

function login(role, loginId) {
  const res = http.post(
    `${__ENV.BASE_URL}/api/${role}/login`,
    JSON.stringify({ loginId, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (res.status !== 200) {
    throw new Error(`[ec2-login] 로그인 실패 ${loginId}: ${res.status} ${res.body}`);
  }
  const jar = res.cookies['SESSION_ID'];
  if (!jar || jar.length === 0) {
    throw new Error(`[ec2-login] ${loginId}: SESSION_ID 쿠키를 못 받음`);
  }
  return jar[0].value;
}
