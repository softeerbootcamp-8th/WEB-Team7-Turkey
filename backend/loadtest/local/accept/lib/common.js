// 배차 accept 부하 테스트 공용 설정·지표.
// #270 규약: 대상 주소는 .env 의 BASE_URL 로 주입하고 스크립트는 고치지 않는다.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');

// 세션 쿠키 이름은 서버 상수와 일치해야 한다(common/auth/SessionCookie.NAME).
export const SESSION_COOKIE = 'SESSION_ID';

// #1(B): 409 는 실패가 아니라 경쟁 패배다. won/lost/error 를 분리해서 센다.
export const won = new Counter('accept_won');       // 200 — 배차 성공
export const lost = new Counter('accept_lost_409');  // 409 — 경쟁 패배(정상)
export const errors = new Counter('accept_error');   // 5xx·타임아웃·그 외 — 유일한 진짜 실패
export const wonMs = new Trend('accept_won_ms', true);
export const lostMs = new Trend('accept_lost_ms', true);

// 시더 산출물(pairs.json)은 각 스크립트 최상단에서 SharedArray 로 읽는다(k6 open() 은 init 컨텍스트 전용):
//   import { SharedArray } from 'k6/data';
//   const pairs = new SharedArray('pairs', () => JSON.parse(open(__ENV.PAIRS_FILE || './seed/pairs.json')));

// 한 건의 accept 요청 — 세션 토큰을 헤더로 실어 보내고(로그인 폭주 회피, README §4),
// 응답을 won/lost/error 로 분류해 지표에 반영한다.
export function accept(sessionId, orderId) {
  const res = http.post(
    `${BASE_URL}/api/rider/requests/${orderId}/accept`,
    null,
    {
      headers: { Cookie: `${SESSION_COOKIE}=${sessionId}` },
      tags: { name: 'accept' }, // http_server_requests 와 대응시키기 위한 태그
    },
  );

  if (res.status === 200) {
    won.add(1);
    wonMs.add(res.timings.duration);
  } else if (res.status === 409) {
    lost.add(1);
    lostMs.add(res.timings.duration);
  } else {
    // 404(만료·없음)도 여기 포함 — 시드가 온전하면 안 나와야 하므로 오류로 취급해 드러낸다.
    errors.add(1);
  }
  // 오류의 정체를 요약에서 바로 보려고 상태코드를 버킷으로 센다(500=풀 고갈/락, 401=세션, 404=상태).
  check(res, {
    'won 200': (r) => r.status === 200,
    'lost 409': (r) => r.status === 409,
    'err 5xx': (r) => r.status >= 500,
    'err 401': (r) => r.status === 401,
    'err 404': (r) => r.status === 404,
    'err other4xx': (r) => r.status >= 400 && r.status < 500 && r.status !== 409 && r.status !== 401 && r.status !== 404,
  });
  return res;
}
