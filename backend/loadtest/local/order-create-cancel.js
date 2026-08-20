// 배송요청 생성(POST /api/customer/deliveries) 부하 테스트 — 생성→취소 사이클.
//
// 이 API 를 고른 이유: 무거운 쓰기 트랜잭션이다. 한 요청이 (1) 지연 만료 정리(expireIfStale),
// (2) 좌표로 요금 재계산 + estimatedFare 대조, (3) order→payment 한 트랜잭션 안에서 포인트 차감
// (MANDATORY), (4) 진행 중 1건 제한 검사(uk_delivery_active_customer), (5) 주문 + ESTIMATE 요금
// 스냅샷 insert 를 한다. quote(순수 계산, 대조군)와 정확히 대비되는 실제 쓰기 경로다.
//
// 왜 "사이클"인가: 주문 생성은 상태 소진형이다. 고객은 진행 중 주문을 1건만 가질 수 있어
// 두 번째 생성부터 409 다. 그래서 매 iteration 에서 생성한 뒤 곧바로 취소(WAITING→CANCELED,
// 환급)해 슬롯을 비운다 — 그래야 같은 계정으로 반복 부하를 걸 수 있다. 측정 대상은 create 이고
// (api:order-create 태그), cancel 은 슬롯을 되돌리는 보조 요청이자 별도 태그(api:order-cancel)로
// 함께 관찰한다.
//
// 초록불 함정 두 개(preconditions.md):
//  - requestKey 는 멱등키다. 같은 값을 재전송하면 새 주문을 만들지 않고 기존 주문을 그대로
//    돌려준다. iteration 마다 반드시 새 UUID 를 뽑아야 실제로 주문이 생성된다(안 그러면 201 을
//    받고도 서버는 아무것도 안 만든다).
//  - estimatedFare 가 서버 재계산값과 다르면 409 다. 고정 경로로 setup 에서 /quote 를 한 번 불러
//    fare.totalFare 를 캐시하고 그대로 실어 보낸다.
//
// 준비: reset-and-seed-local.sql → seed-loadtest-order-create.sql (lt_o1..N 고객, 진행 중 주문 없음).
//   cd backend
//   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/reset-and-seed-local.sql
//   docker compose exec -T mysql mysql -uturkey -plocal turkey < scripts/seed-loadtest-order-create.sql
//
// 실행(계단 램프 예):
//   docker compose run --rm -e BASE_URL=http://app:8080 -e CUSTOMER_COUNT=200 \
//     -e MAX_VU=200 -e STEPS=5 k6 run --tag testid=order-create-... /scripts/local/order-create-cancel.js
import http from 'k6/http';
import { check, fail } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const PASSWORD = __ENV.PASSWORD || 'aa';

// 계정 목록. 진행 중 주문이 없는 고객이어야 첫 생성이 성공한다.
//  - 기본값은 기본 시드의 c5·c6(진행 중 주문 없는 유일한 고객 2명).
//  - CUSTOMER_COUNT=200 을 주면 seed-loadtest-order-create.sql 이 만든 lt_o1..lt_o200 을 쓴다.
//    VU 당 고객 1명이 되게 늘리는 것이 목적이다 — 계정을 공유하면 create/cancel 이 같은 행에
//    직렬화돼 진행 중 1건 제한 경합이 과장된다.
const CUSTOMERS = __ENV.CUSTOMER_COUNT
  ? Array.from({ length: Number(__ENV.CUSTOMER_COUNT) }, (_, i) => `${__ENV.CUSTOMER_PREFIX || 'lt_o'}${i + 1}`)
  : (__ENV.CUSTOMERS || 'c5,c6').split(',');

// 최대 VU. CUSTOMER_COUNT 보다 크게 잡으면 여러 VU 가 한 고객을 공유한다. 이 시나리오에서는
// 그러면 같은 계정에 create 와 cancel 이 뒤섞여 진행 중 1건 제한 409 가 튀므로 특히 피해야 한다.
const MAX_VU = Number(__ENV.MAX_VU || 60);

// 계단 램프. 위치 갱신 시나리오와 동일한 규약(STEPS/HOLD_SECONDS/RAMP_SECONDS)이다. STEPS 를
// 주지 않으면 기존 4단 램프 그대로다. 한 단은 90초 이상이어야 서버측 지표가 채워진다.
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

// DURATION 을 주면 MAX_VU 로 즉시 올려 그 시간만큼 평탄하게 유지한다 — 웜업 전용 모드다
// (스킬 원칙 3). 측정 VU 와 동일한 부하로 데워야 Hikari 대기열 등 동시성 경로까지 JIT 컴파일이
// 끝난다. 결과는 버린다. DURATION 을 안 주면 기존 계단/램프 그대로라 이전 런과 비교가 유지된다.
const DURATION = __ENV.DURATION || '';
const flatWarmup = () => [
  { duration: '5s', target: MAX_VU },
  { duration: DURATION, target: MAX_VU },
  { duration: '5s', target: 0 },
];

export const options = {
  stages: DURATION ? flatWarmup() : (STEPS > 0 ? staircase() : [
    { duration: '20s', target: Math.max(1, Math.round(MAX_VU / 6)) },
    { duration: '30s', target: Math.max(1, Math.round(MAX_VU / 2)) },
    { duration: '30s', target: MAX_VU },
    { duration: '10s', target: 0 },
  ]),
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // 사용자 대면 쓰기 요청이다. 위치 갱신(백그라운드, 300ms)보다 여유를 두되, 500ms 를 넘기면
    // 쓰기 트랜잭션이 병목이라는 신호다.
    'http_req_duration{api:order-create}': ['p(95)<500'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  setupTimeout: __ENV.SETUP_TIMEOUT || '900s',
};

// 고정 경로. straight distance 는 서버가 좌표로 계산한다 — max_delivery_distance_meters(30km)
// 안이어야 한다. 강남 테헤란로 → 시청 부근(직선 약 8km)이라 요금은 base 5000 + 거리요금 수준.
const PICKUP = {
  roadAddress: '서울 강남구 테헤란로 152',
  postalCode: '06236',
  latitude: 37.5006,
  longitude: 127.0366,
};
const DESTINATION = {
  roadAddress: '서울 중구 세종대로 110',
  postalCode: '04524',
  latitude: 37.5665,
  longitude: 126.9780,
};
const ITEM_TYPE = 'DOCUMENT'; // 할증 0 — 요금 대조를 단순하게.

// 36자 UUID 형식의 요청키. 멱등키라 iteration 마다 유일해야 새 주문이 생긴다. VU·ITER 를
// 앞 12 hex 에 심어 충돌을 원천 차단하고(같은 (VU,ITER) 조합은 런 안에서 유일), 나머지는 랜덤.
function requestKey(vu, iter) {
  const tag = vu.toString(16).padStart(4, '0') + iter.toString(16).padStart(8, '0'); // 12 hex
  let rest = '';
  for (let i = 0; i < 20; i += 1) rest += Math.floor(Math.random() * 16).toString(16);
  const s = tag + rest; // 32 hex
  return `${s.slice(0, 8)}-${s.slice(8, 12)}-${s.slice(12, 16)}-${s.slice(16, 20)}-${s.slice(20, 32)}`;
}

// 로그인은 계정당 1회만 하고, 요금 견적도 한 번만 받아 캐시한다(측정 구간에 bcrypt·견적 계산이
// 섞이지 않게).
export function setup() {
  const sessions = CUSTOMERS.map((loginId) => {
    const res = http.post(
      `${BASE}/api/customer/login`,
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

  // /quote 는 비로그인 경로다. 고정 경로 요금을 한 번 받아 모든 create 에 재사용한다.
  const quoteRes = http.post(
    `${BASE}/api/customer/deliveries/quote`,
    JSON.stringify({ itemType: ITEM_TYPE, pickupAddress: PICKUP, destinationAddress: DESTINATION }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (quoteRes.status !== 200) {
    fail(`견적 실패: ${quoteRes.status} ${quoteRes.body}`);
  }
  const fare = quoteRes.json('data.fare.totalFare');
  if (!fare) {
    fail(`견적 응답에 totalFare 없음: ${quoteRes.body}`);
  }

  console.log(`세션 ${sessions.length}개 확보 (${CUSTOMERS[0]} … ${CUSTOMERS[CUSTOMERS.length - 1]}), 요금 ${fare}, 최대 VU ${MAX_VU}`);
  if (MAX_VU > CUSTOMERS.length) {
    console.warn(`주의: MAX_VU(${MAX_VU}) > 계정 수(${CUSTOMERS.length}) — VU 여러 개가 한 고객을 공유해 진행 중 1건 제한 409 가 튄다`);
  }
  console.log(`RAMP_START_EPOCH=${Math.floor(Date.now() / 1000)}`);
  return { sessions, fare };
}

export default function (data) {
  // VU 를 고객에 고르게 배분한다. VU 당 고객 1명이면 create/cancel 이 직렬화돼 409 가 없다.
  const session = data.sessions[(__VU - 1) % data.sessions.length];
  const cookie = { 'Content-Type': 'application/json', Cookie: `SESSION_ID=${session}` };

  const createBody = JSON.stringify({
    requestKey: requestKey(__VU, __ITER),
    estimatedFare: data.fare,
    itemType: ITEM_TYPE,
    pickup: PICKUP,
    destination: DESTINATION,
    sender: { name: '발송인', phoneNumber: '010-1234-5678' },
    recipient: { name: '수령인', phoneNumber: '010-8765-4321' },
  });

  const createRes = http.post(`${BASE}/api/customer/deliveries`, createBody, {
    headers: cookie,
    tags: { api: 'order-create' },
  });

  const created = check(createRes, {
    // 컨트롤러가 @ResponseStatus(CREATED) 라 성공은 201 이다. 200 을 기대하면 안 된다.
    'create 201': (r) => r.status === 201,
    '인증 실패 아님': (r) => r.status !== 401,
    // 409 면 진행 중 1건 제한(슬롯이 안 비었다)이거나 요금 대조 실패다 — 둘 다 이 시나리오에선
    // 버그 신호이므로 실패로 본다.
    '409 아님': (r) => r.status !== 409,
  });

  if (!created || createRes.status !== 201) {
    // 생성이 안 됐으면 취소할 주문이 없다. 다음 iteration 으로 넘어간다(슬롯이 남아 있으면
    // 다음 create 가 409 로 이어질 수 있으니 원인은 로그·지표로 판단).
    return;
  }

  const deliveryId = createRes.json('data.deliveryId');
  const cancelRes = http.patch(
    `${BASE}/api/customer/deliveries/${deliveryId}/cancel`,
    JSON.stringify({ reason: 'loadtest cycle' }),
    // name 을 상수로 고정하지 않으면 k6 는 URL(=deliveryId 포함) 을 name 라벨로 쓴다. iteration
    // 마다 deliveryId 가 달라 매번 새 시계열이 생기고, 그게 모든 k6 메트릭에 곱해져 Prometheus
    // 가 수백만 시리즈로 OOM 난다(실측: 5분 구간만으로 cancel URL 69,234종). 상수 name 으로
    // 묶어 카디널리티를 1 로 만든다. 임계값·태그는 api 로 그대로 건다.
    { headers: cookie, tags: { api: 'order-cancel', name: '/api/customer/deliveries/{id}/cancel' } },
  );
  check(cancelRes, {
    'cancel 200': (r) => r.status === 200,
  });
}
