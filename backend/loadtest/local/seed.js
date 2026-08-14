// #373: #259 부하테스트(SSE arm / Polling arm)가 쓸 실험 데이터를 순수 k6 API 호출로 준비한다.
//
// 이 파일은 그 자체로 실행하는 스크립트가 아니라, arm 스크립트(SSE/Polling)가
// setup() 안에서 import 해 쓰는 모듈이다:
//
//   import { seedPairs } from './local/seed.js';
//   export function setup() {
//     return seedPairs(Number(__ENV.N || 10));
//   }
//
// ── 왜 순수 API 경유인가 (#373 계약 확정, 사람 확인) ──────────────────────────
// SQL 로 delivery_order 를 직접 꽂는 건 더 빠르지만, active_rider_id 생성 컬럼·
// uk_delivery_active_rider 유니크·Redis 세션을 전부 손으로 맞춰야 해서 실수하기 쉽다
// (오늘 수동 시딩에서 order_fare_snapshot 을 빠뜨려 라이더 배차 타임아웃 스캐너가
// IllegalStateException 으로 계속 죽는 걸 직접 겪었다). API 경유는 느리지만 그
// 자체가 실제 도메인 제약을 통과했다는 검증이 된다 — 시딩이 곧 계약 테스트다.
//
// ── 세 가지 관문이 전부 열려 있어서 가능하다 (#373 조사, 2026-08-11) ─────────
// 1. POST /api/phone-verifications 응답의 debugCode 가 local 프로파일에서만 채워진다
//    (PhoneVerificationResponse.from). 그 값을 그대로 confirm 에 실어 인증 완료 토큰을 받는다.
// 2. 필수 약관 동의는 환경변수로 받는다(아래 CUSTOMER_TERM_IDS / RIDER_TERM_IDS).
//    원래는 "term 테이블이 0행이라 agreedTermIds: [] 로 통과한다"를 전제했는데, 그건
//    **Flyway 만 적용된 새 볼륨에서만** 맞다. reset-and-seed-local.sql(로컬 셋업 문서가
//    실행하라고 안내하는 그 스크립트)이 필수 약관 4개를 넣으므로, 그 DB에서는 빈 배열이
//    400 "필수 약관에 모두 동의해야 합니다"로 막힌다(실측 확인).
//    term_id 는 auto_increment 라 환경마다 달라 하드코딩하지 않는다. 조회 API(/api/terms)는
//    #72 로 아직 없어서, 그것이 생기면 이 환경변수를 지우고 조회로 대체한다.
// 3. MockPaymentGateway.confirm 은 authToken 이 "mock_decline" 이 아니면 무조건 승인한다.
//
// fare_policy 활성 정책은 이 스크립트가 만들지 않는다 — 실행 전에 별도로 보장해야 한다
// (PR #460 리뷰 반영으로 Flyway V19에서 뺐다, #373). 로컬 DB는
// backend/loadtest/seed-fare-policy.sql을 먼저 실행해 둔다.

import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 회차 격리(#373 계약 확정, 사람 확인): loginId·phoneNumber 에 이 접미사를 붙여
// 이전 회차 데이터와 충돌하지 않게 한다. 안 주면 매 실행 타임스탬프로 새로 만든다 —
// 재현이 필요하면 -e RUN_ID=fixed-001 처럼 고정값을 직접 지정한다.
const RUN_ID = __ENV.RUN_ID || `r${Date.now()}`;

const CHARGE_AMOUNT = 100000; // MIN_CHARGE_AMOUNT~MAX_CHARGE_AMOUNT, CHARGE_AMOUNT_UNIT(1000) 배수.
const ITEM_TYPE = 'DOCUMENT';

// 필수 약관 동의 목록(위 「관문 2」). 안 주면 빈 배열이라 term 0행 DB에서는 그대로 통과하고,
// 약관이 있는 DB에서는 checkSignup 이 무엇을 어떻게 넘겨야 하는지 알려주며 멈춘다.
//   -e CUSTOMER_TERM_IDS=1,2,4 -e RIDER_TERM_IDS=1,2,5
const CUSTOMER_TERM_IDS = parseIds(__ENV.CUSTOMER_TERM_IDS);
const RIDER_TERM_IDS = parseIds(__ENV.RIDER_TERM_IDS);

function parseIds(raw) {
  return (raw || '').split(',').map((s) => s.trim()).filter(Boolean).map(Number);
}

/** 회원가입 전용 응답 검사. 약관 때문에 막힌 경우를 알아보고 조치 방법을 알려준다. */
function checkSignup(res, label, role, termIds) {
  if (res.status >= 200 && res.status < 300) {
    return res.json('data');
  }
  if (res.body && res.body.indexOf('필수 약관') >= 0) {
    const envName = role === 'CUSTOMER' ? 'CUSTOMER_TERM_IDS' : 'RIDER_TERM_IDS';
    throw new Error(
      `[seed] ${label}: 이 DB에는 필수 약관이 있어 동의 목록이 필요하다` +
      ` (지금 넘긴 값: [${termIds.join(',')}]).\n` +
      `  아래로 ID를 확인해 -e ${envName}=<쉼표구분> 으로 넘길 것:\n` +
      `    SELECT term_id FROM term WHERE is_active = 1 AND is_required = 1\n` +
      `      AND target_role IN ('COMMON', '${role}');`
    );
  }
  throw new Error(`[seed] ${label} 실패: status=${res.status} body=${res.body}`);
}

// 서울 내 좌표 두 쌍(강남 → 송파) — 최대 배송거리(30km, seed-fare-policy.sql)를 넘지 않는
// 임의의 고정 경로. 여러 쌍을 만들 때 좌표를 다양화하지 않는 이유는 #259 의 종속변인
// (지연·용량)이 좌표가 아니라 동시 연결 수 N 에 달려 있어서다.
const PICKUP = { roadAddress: '서울 강남구 테헤란로 152', detailAddress: '5층', postalCode: '06236', latitude: 37.5006, longitude: 127.0366 };
const DESTINATION = { roadAddress: '서울 송파구 올림픽로 300', detailAddress: '1동', postalCode: '05551', latitude: 37.5145, longitude: 127.1059 };

function jsonHeaders(extra) {
  return { headers: Object.assign({ 'Content-Type': 'application/json' }, extra || {}) };
}

function checkOk(res, label) {
  if (res.status < 200 || res.status >= 300) {
    throw new Error(`[seed] ${label} 실패: status=${res.status} body=${res.body}`);
  }
  return res.json('data');
}

function sessionCookieOf(res, label) {
  const jar = res.cookies['SESSION_ID'];
  if (!jar || jar.length === 0) {
    throw new Error(`[seed] ${label}: SESSION_ID 쿠키를 못 받음. body=${res.body}`);
  }
  return jar[0].value;
}

/** 인증요청 → debugCode 확인 → 인증완료 토큰. 가입에 필요한 phoneVerificationToken 을 반환한다. */
function verifyPhone(phoneNumber, purpose) {
  const reqRes = http.post(
    `${BASE_URL}/api/phone-verifications`,
    JSON.stringify({ phoneNumber, purpose }),
    jsonHeaders()
  );
  const reqData = checkOk(reqRes, `phone-verifications 요청(${phoneNumber})`);
  const debugCode = reqData.debugCode;
  if (!debugCode) {
    // local 프로파일이 아니면 이 값이 null 이라 시딩이 여기서 막힌다 — 의도된 실패다.
    throw new Error(
      `[seed] debugCode 가 비어 있음(local 프로파일이 아닌 서버를 겨냥한 것 아닌지 확인). phoneNumber=${phoneNumber}`
    );
  }

  const confirmRes = http.post(
    `${BASE_URL}/api/phone-verifications/confirm`,
    JSON.stringify({ phoneNumber, purpose, code: debugCode }),
    jsonHeaders()
  );
  const confirmData = checkOk(confirmRes, `phone-verifications/confirm(${phoneNumber})`);
  return confirmData.verificationToken;
}

/** 고객 1명: 가입 → 로그인 → 충전 준비/승인 → 세션 쿠키. */
function seedCustomer(index) {
  const loginId = `lt_cust_${RUN_ID}_${index}`;
  const phoneNumber = phoneNumberFor('1', RUN_ID, index);
  const password = 'loadtest1234!';

  const phoneToken = verifyPhone(phoneNumber, 'SIGNUP');

  const signupRes = http.post(
    `${BASE_URL}/api/customer/signup`,
    JSON.stringify({
      loginId,
      password,
      passwordConfirm: password,
      name: `부하고객${index}`,
      phoneNumber,
      phoneVerificationToken: phoneToken,
      agreedTermIds: CUSTOMER_TERM_IDS,
    }),
    jsonHeaders()
  );
  checkSignup(signupRes, `customer/signup(${loginId})`, 'CUSTOMER', CUSTOMER_TERM_IDS);

  const loginRes = http.post(
    `${BASE_URL}/api/customer/login`,
    JSON.stringify({ loginId, password }),
    jsonHeaders()
  );
  checkOk(loginRes, `customer/login(${loginId})`);
  const sessionId = sessionCookieOf(loginRes, `customer/login(${loginId})`);
  const cookieHeader = { Cookie: `SESSION_ID=${sessionId}` };

  const chargeRequestKey = uuidLike(`chg-${RUN_ID}-${index}`);
  const prepareRes = http.post(
    `${BASE_URL}/api/customer/points/charges`,
    JSON.stringify({ chargeRequestKey, amount: CHARGE_AMOUNT, paymentMethod: 'CARD' }),
    jsonHeaders(cookieHeader)
  );
  const prepareData = checkOk(prepareRes, `points/charges 준비(${loginId})`);

  const confirmChargeRes = http.post(
    `${BASE_URL}/api/customer/points/charges/${prepareData.pointChargeId}/confirm`,
    JSON.stringify({ amount: CHARGE_AMOUNT, authToken: `mock_auth_${RUN_ID}_${index}` }),
    jsonHeaders(cookieHeader)
  );
  checkOk(confirmChargeRes, `points/charges 승인(${loginId})`);

  return { sessionId, cookieHeader, loginId };
}

/** 라이더 1명: 가입 → 로그인 → GO_ONLINE → 세션 쿠키. */
function seedRider(index) {
  const loginId = `lt_rider_${RUN_ID}_${index}`;
  const phoneNumber = phoneNumberFor('2', RUN_ID, index);
  const password = 'loadtest1234!';

  const phoneToken = verifyPhone(phoneNumber, 'SIGNUP');

  const signupRes = http.post(
    `${BASE_URL}/api/rider/signup`,
    JSON.stringify({
      loginId,
      password,
      passwordConfirm: password,
      name: `부하라이더${index}`,
      phoneNumber,
      phoneVerificationToken: phoneToken,
      agreedTermIds: RIDER_TERM_IDS,
    }),
    jsonHeaders()
  );
  checkSignup(signupRes, `rider/signup(${loginId})`, 'RIDER', RIDER_TERM_IDS);

  const loginRes = http.post(
    `${BASE_URL}/api/rider/login`,
    JSON.stringify({ loginId, password }),
    jsonHeaders()
  );
  checkOk(loginRes, `rider/login(${loginId})`);
  const sessionId = sessionCookieOf(loginRes, `rider/login(${loginId})`);
  const cookieHeader = { Cookie: `SESSION_ID=${sessionId}` };

  const onlineRes = http.patch(
    `${BASE_URL}/api/rider/operating-status`,
    JSON.stringify({ action: 'GO_ONLINE' }),
    jsonHeaders(cookieHeader)
  );
  checkOk(onlineRes, `operating-status GO_ONLINE(${loginId})`);

  return { sessionId, cookieHeader, loginId };
}

/**
 * N쌍의 (고객, 라이더, ASSIGNED 상태 배송)을 만든다. arm 스크립트가 이 결과를 setup() 반환값으로
 * 그대로 쓰면 된다.
 *
 * 라이더 i는 정확히 주문 i만 수락한다(#373 요구사항) — ADR-006 조건부 UPDATE 경쟁이 시딩
 * 단계에서는 일어나지 않는다(라이더 수만큼 주문도 있고 1:1 로 짝지어서). 여러 라이더가 같은
 * 주문에 몰리는 경쟁 자체를 재는 건 #446/#56 류의 별도 실험이지 이 시딩의 책임이 아니다.
 *
 * @returns {{customerSession: string, riderSession: string, deliveryId: number}[]}
 */
export function seedPairs(n) {
  const pairs = [];
  for (let i = 1; i <= n; i++) {
    const customer = seedCustomer(i);
    const rider = seedRider(i);

    const requestKey = uuidLike(`ord-${RUN_ID}-${i}`);
    const quoteRes = http.post(
      `${BASE_URL}/api/customer/deliveries/quote`,
      JSON.stringify({ itemType: ITEM_TYPE, pickupAddress: PICKUP, destinationAddress: DESTINATION }),
      jsonHeaders()
    );
    const quote = checkOk(quoteRes, `deliveries/quote(${i})`);

    const createRes = http.post(
      `${BASE_URL}/api/customer/deliveries`,
      JSON.stringify({
        requestKey,
        estimatedFare: quote.fare.totalFare,
        itemType: ITEM_TYPE,
        pickup: PICKUP,
        destination: DESTINATION,
        sender: { name: `부하고객${i}`, phoneNumber: phoneNumberFor('1', RUN_ID, i) },
        recipient: { name: `부하수령인${i}`, phoneNumber: phoneNumberFor('3', RUN_ID, i) },
      }),
      jsonHeaders(customer.cookieHeader)
    );
    const created = checkOk(createRes, `deliveries 생성(${i})`);
    const deliveryId = created.deliveryId;

    const acceptRes = http.post(
      `${BASE_URL}/api/rider/requests/${deliveryId}/accept`,
      null,
      jsonHeaders(rider.cookieHeader)
    );
    checkOk(acceptRes, `requests/${deliveryId}/accept(라이더${i})`);

    pairs.push({
      customerSession: customer.sessionId,
      riderSession: rider.sessionId,
      deliveryId,
    });
  }
  return { pairs, runId: RUN_ID };
}

/**
 * 010-XXXX-YYYY 형태의 유효한 휴대전화번호를 회차·인덱스로부터 결정적으로 만든다.
 * prefix(1=고객/2=라이더/3=수령인)로 같은 회차 안에서도 역할별로 겹치지 않게 한다.
 * member.phone_number 는 UNIQUE라 회차가 다르면 반드시 다른 번호가 나와야 한다 — RUN_ID 를
 * 숫자로 해시해 뒷자리에 섞는다.
 */
function phoneNumberFor(prefix, runId, index) {
  const hash = Math.abs(hashCode(runId)) % 10000;
  const mid = String((Number(prefix) * 1000 + hash) % 10000).padStart(4, '0');
  const last = String(index % 10000).padStart(4, '0');
  return `010-${mid}-${last}`;
}

function hashCode(str) {
  let h = 0;
  for (let i = 0; i < str.length; i++) {
    h = (h << 5) - h + str.charCodeAt(i);
    h |= 0;
  }
  return h;
}

/** DeliveryCreateRequest.requestKey 등은 정확히 36자를 요구한다(@Size(min=36, max=36)) —
 * 진짜 UUID 형식일 필요는 없고 길이만 맞으면 통과하지만, 디버깅 편의상 UUID 모양을 흉내낸다. */
function uuidLike(seed) {
  const base = `${seed}-00000000-0000-0000-000000000000`;
  return base.slice(0, 36);
}
