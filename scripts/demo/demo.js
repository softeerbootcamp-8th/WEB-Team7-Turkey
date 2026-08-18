// Turkey 퀵배송 매칭 서비스 — 고객/라이더 콜 매칭 라이브 데모 스크립트
//
// 고객·라이더 계정을 새로 만들고 로그인한 뒤, 고객이 배송요청을 생성하고
// 라이더가 콜 목록에서 그 요청을 수락하는 전 과정을 브라우저 두 창(고객/라이더)에서
// 실제로 재현한다. 발표 중 화면 두 개를 나란히 띄워 "콜 잡는 과정"을 보여주는 용도.
//
// 사전 준비 (이미 되어 있지 않다면):
//   1) backend: docker compose up -d (또는 로컬 MySQL/Redis) 후
//      ./gradlew bootRun --args='--spring.profiles.active=local'
//   2) frontend: frontend/.env.local 에 VITE_KAKAO_MAP_KEY 설정 후 pnpm dev
//
// 실행 (frontend/.env.local에 VITE_KAKAO_MAP_KEY가 있으면 그대로 실행):
//   node demo.js
//
// 환경 변수:
//   BASE_URL    프론트엔드 주소 (기본 http://localhost:5173)
//   HEADLESS    true/false (기본 false — 발표 중엔 창이 보여야 하므로 headed가 기본)
//   SLOWMO      각 액션 사이 지연(ms), 발표 중 눈으로 따라오기 좋게 (기본 250)
//   KEEP_OPEN   true/false (기본 true — 끝나고도 창을 열어 둔다. Ctrl+C로 종료)
//   STUB_KAKAO  true/false (기본 false — 카카오 주소검색 팝업만 고정 주소로 건너뛰고, 좌표
//               변환·지도 렌더링은 실제 카카오맵 SDK를 그대로 쓴다. dapi.kakao.com에 나가는
//               네트워크 자체가 막힌 환경(예: 일부 사내망·샌드박스)에서만 true로 켜서 지오코더·
//               지도까지 고정 좌표로 흉내낸다 — 발표 현장(일반 인터넷)에서는 켤 필요 없음)
//
// 고객 포인트 지갑은 가입 직후 0P라 배송요청 생성 전에 모의 결제(MockPaymentModal, 실제
// PG 아님)로 50,000P를 충전한다 — 실제 결제 화면 흐름 그대로라 발표에서 보여줘도 자연스럽다.

const { chromium } = require('playwright')

// 이 실행 환경엔 Playwright 번들 브라우저 대신 사전 설치된 Chromium이
// PLAYWRIGHT_BROWSERS_PATH 아래 고정 경로로 준비되어 있다(빌드 번호 불일치로 기본
// 탐색이 실패할 수 있어 명시적으로 지정한다). 다른 환경에서 실행할 땐 이 경로가 없으면
// undefined로 두어 Playwright 기본 브라우저를 쓰게 한다.
const fs = require('fs')
const PRESET_CHROMIUM = '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'
const EXECUTABLE_PATH = fs.existsSync(PRESET_CHROMIUM) ? PRESET_CHROMIUM : undefined

const BASE_URL = process.env.BASE_URL || 'http://localhost:5173'
const HEADLESS = process.env.HEADLESS === 'true'
const SLOWMO = Number(process.env.SLOWMO ?? 250)
const KEEP_OPEN = process.env.KEEP_OPEN !== 'false'
// 이 실행 환경(샌드박스)의 아웃바운드 정책이 dapi.kakao.com을 막아 실제 카카오맵 지오코더를
// 쓸 수 없어 검증용으로 만든 스위치다. 실제 발표 환경(일반 인터넷)에서는 켤 필요가 없다 —
// 기본값 false로 두면 진짜 카카오 지오코딩·지도가 그대로 동작한다.
const STUB_KAKAO = process.env.STUB_KAKAO === 'true'

const PICKUP_ADDRESS = {
  zonecode: '06232',
  roadAddress: '서울 강남구 테헤란로 152',
  jibunAddress: '서울 강남구 역삼동 737',
  autoRoadAddress: '',
  autoJibunAddress: '',
  address: '서울 강남구 테헤란로 152',
}
const PICKUP_COORDS = { latitude: 37.5006, longitude: 127.0364 }
const DESTINATION_ADDRESS = {
  zonecode: '04524',
  roadAddress: '서울 중구 세종대로 110',
  jibunAddress: '서울 중구 태평로1가 31',
  autoRoadAddress: '',
  autoJibunAddress: '',
  address: '서울 중구 세종대로 110',
}
const DESTINATION_COORDS = { latitude: 37.5663, longitude: 126.9779 }

// STUB_KAKAO=true일 때만 쓰는 최소 카카오맵 SDK 대역 — 지오코더는 고정 좌표를 즉시 반환하고,
// 지도 렌더 관련 클래스(Map/Marker/Polyline 등)는 각 컴포넌트가 크래시 없이 그리기만 시도할 수
// 있게 하는 무동작(no-op) 뼈대다. 실제 지도 타일은 그리지 않는다(네트워크 자체가 막혀 있으므로).
function kakaoStubSource(pickupCoords, destinationCoords) {
  return `
(function () {
  function resolveCoords(address) {
    if (typeof address === 'string' && address.indexOf('${DESTINATION_ADDRESS.roadAddress}') !== -1) {
      return ${JSON.stringify(DESTINATION_COORDS)};
    }
    return ${JSON.stringify(PICKUP_COORDS)};
  }
  function LatLng(lat, lng) { this.getLat = function(){ return lat; }; this.getLng = function(){ return lng; }; }
  function LatLngBounds() { this.extend = function(){}; }
  function NoopMapObject() { this.setMap = function(){}; }
  function Map() { this.setBounds = function(){}; this.relayout = function(){}; this.setCenter = function(){}; }
  window.kakao = window.kakao || {};
  window.kakao.maps = {
    load: function (cb) { cb(); },
    LatLng: LatLng,
    LatLngBounds: LatLngBounds,
    Map: Map,
    Marker: NoopMapObject,
    Polyline: NoopMapObject,
    MarkerImage: function () {},
    Size: function () {},
    Point: function () {},
    services: {
      Status: { OK: 'OK', ZERO_RESULT: 'ZERO_RESULT', ERROR: 'ERROR' },
      Geocoder: function () {
        this.addressSearch = function (address, callback) {
          var coords = resolveCoords(address);
          callback([{ y: String(coords.latitude), x: String(coords.longitude) }], 'OK');
        };
      },
    },
  };
})();
`
}

function log(actor, message) {
  const ts = new Date().toISOString().split('T')[1].replace('Z', '')
  console.log(`[${ts}] [${actor}] ${message}`)
}

function uniqueSuffix() {
  return `${Date.now()}${Math.floor(Math.random() * 1000)}`
}

function randomPhoneNumber() {
  // PHONE_NUMBER_PATTERN: /^01(?:0|1|[6-9])\d{3,4}\d{4}$/ — 010 + 8자리
  const rest = String(Math.floor(10000000 + Math.random() * 89999999))
  return `010${rest}`
}

// Daum 우편번호 검색 팝업을 실제로 띄우는 대신, 고정된 실제 서울 주소로 즉시 완료 처리한다.
// 이후 좌표 변환(geocodeAddress)은 실제 카카오맵 Geocoder를 그대로 호출하므로 좌표는 진짜다.
function daumPostcodeStub({ pickup, destination }) {
  window.daum = {
    Postcode: function Postcode(opts) {
      this.open = function open(openOpts) {
        const isDestination = Boolean(openOpts && openOpts.popupTitle && openOpts.popupTitle.includes('도착지'))
        const data = isDestination ? destination : pickup
        setTimeout(() => opts.oncomplete(data), 200)
      }
    },
  }
}

async function fillById(page, id, value) {
  await page.locator(`#${id}`).fill(value)
}

async function signup(page, actor, { loginId, password, name, phoneNumber }) {
  log(actor, `회원가입 시작 (loginId=${loginId})`)
  // 방금 페이지가 로드된 직후라 첫 입력이 초기 마운트 커밋 이전에 들어가면 사라질 수 있다.
  await page.waitForTimeout(500)
  await fillById(page, 'userId', loginId)
  await page.getByRole('button', { name: '중복 확인' }).click()
  await page.getByText('사용할 수 있는 아이디입니다.').waitFor({ timeout: 10_000 })

  await fillById(page, 'password', password)
  await fillById(page, 'userName', name)
  await fillById(page, 'userPhone', phoneNumber)

  await page.getByRole('button', { name: '인증번호 전송' }).click()
  // 비밀번호 확인 입력칸은 없어졌고(회원가입 UX 개선), 인증번호도 더 이상 #signup-debug-code
  // 요소로 노출되지 않는다. 대신 토스트(PhoneVerificationToast)로 뜨면서 화면이 그 값을 인증번호
  // 입력칸(#verificationCode)에 자동으로 채운다 — 자동 채움 값을 그대로 읽어 확인만 하면 된다.
  // 혹시 비어 있으면 토스트 문구("인증번호는 000000입니다")에서 6자리를 뽑아 채운다.
  const codeInput = page.locator('#verificationCode')
  await codeInput.waitFor({ timeout: 10_000 })
  let code = (await codeInput.inputValue()).match(/(\d{6})/)?.[1]
  if (!code) {
    const toastText = await page.getByRole('status').filter({ hasText: '인증번호' }).innerText({ timeout: 10_000 })
    code = toastText.match(/(\d{6})/)?.[1]
    if (!code) {
      throw new Error(`${actor}: 로컬 테스트 인증번호를 읽지 못했습니다: ${toastText}`)
    }
    await fillById(page, 'verificationCode', code)
  }
  log(actor, `인증번호 확인 (${code})`)
  await page.getByRole('button', { name: '인증 확인' }).click()
  await page.getByText('휴대전화 인증이 완료되었습니다.').waitFor({ timeout: 10_000 })

  await page.getByRole('button', { name: '가입하기' }).click()
  await page.waitForURL(/\/(customer|rider)\/login/, { timeout: 10_000 })
  log(actor, '회원가입 완료')
}

async function login(page, actor, { loginId, password }) {
  log(actor, '로그인 시도')
  // 방금 페이지가 로드된 직후라 첫 입력이 초기 마운트 커밋 이전에 들어가면 사라질 수 있다.
  await page.waitForTimeout(500)
  await fillById(page, 'userId', loginId)
  await fillById(page, 'password', password)
  await page.getByRole('button', { name: '로그인' }).click()
  // "/customer(/|$)" 같은 정규식은 이미 "/customer/login" 자체와도 매치되어(부분 문자열) 클릭
  // 직후 즉시 통과해 버린다 — 로그인 성공 후 실제 목적지(정확히 /customer 또는 /rider)로만 판정한다.
  await page.waitForURL((url) => url.pathname === '/customer' || url.pathname === '/rider', { timeout: 10_000 })
  log(actor, '로그인 성공')
}

// 새로 가입한 고객의 포인트 지갑은 0P다. 배송요청 생성이 요금만큼 포인트를 차감하므로(402
// PAYMENT_REQUIRED 방지) 모의 결제(MockPaymentModal)로 미리 충전한다 — 실제 PG 연동은 없다.
async function chargePoints(page, amount) {
  log('고객', `포인트 충전 시작 (${amount.toLocaleString('ko-KR')}P)`)
  await page.goto(`${BASE_URL}/customer/points/charge`)
  await page.waitForTimeout(500)
  await page.locator('#charge-amount').fill(String(amount))
  await page.getByRole('button', { name: /결제하기$/ }).click()

  const dialog = page.getByRole('dialog', { name: '결제 정보를 확인해 주세요' })
  await dialog.waitFor({ timeout: 10_000 })
  await dialog.getByPlaceholder('0000 0000 0000 0000').fill('4000123456789010')
  await dialog.getByPlaceholder('MM/YY').fill('1230')
  await dialog.getByPlaceholder('뒷면 3자리').fill('123')
  await dialog.getByPlaceholder('앞 2자리').fill('12')
  await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button', { name: /결제하기$/ }).click()

  await page.getByRole('heading', { name: '충전이 완료됐습니다' }).waitFor({ timeout: 10_000 })
  log('고객', '포인트 충전 완료')
}

async function runCustomer(context, credentials) {
  const page = await context.newPage()
  page.on('dialog', (dialog) => dialog.accept())
  await page.addInitScript(daumPostcodeStub, { pickup: PICKUP_ADDRESS, destination: DESTINATION_ADDRESS })
  if (STUB_KAKAO) {
    await page.route('**dapi.kakao.com/v2/maps/sdk.js**', (route) =>
      route.fulfill({ contentType: 'application/javascript', body: kakaoStubSource() }))
  }

  await page.goto(`${BASE_URL}/customer/signup`)
  await signup(page, '고객', credentials)
  await page.goto(`${BASE_URL}/customer/login`)
  await login(page, '고객', credentials)

  await chargePoints(page, 50_000)

  log('고객', '홈 화면에서 배송요청 시작')
  await page.goto(`${BASE_URL}/customer`)
  await page.waitForTimeout(500)
  await page.getByRole('link', { name: '퀵 부르기' }).click()
  await page.waitForURL(/\/customer\/deliveries\/new$/)
  await page.waitForTimeout(300)

  await page.getByText('소형', { exact: true }).click()

  // AddressSearch 버튼은 <label>도로명 주소<button>...</button></label> 구조 안에 있다.
  // button은 labelable element라 접근성 트리에서 버튼 자체 텍스트("출발지 검색 *")가 아니라
  // 감싼 label의 텍스트("도로명 주소")가 accessible name이 된다 — fieldset(group)으로 스코프해야 한다.
  // "우편번호 …" 텍스트는 좌표 변환(geocode) 전에도 즉시 뜬다 — 실제로 좌표까지 채워졌는지
  // 확인하려면 "좌표 <숫자>, <숫자>" 까지 나온 걸 기다려야 한다(빈 문자열이면 아직 진행 중).
  log('고객', '출발지 주소 검색')
  await page.getByRole('group', { name: '출발지' }).getByRole('button', { name: '도로명 주소' }).click()
  await page.getByText(/우편번호 \d+ · 좌표 -?\d/).waitFor({ timeout: 20_000 })

  log('고객', '도착지 주소 검색')
  await page.getByRole('group', { name: '도착지' }).getByRole('button', { name: '도로명 주소' }).click()
  await page.getByText(/우편번호 \d+ · 좌표 -?\d/).nth(1).waitFor({ timeout: 20_000 })

  const pickupFieldset = page.locator('fieldset', { hasText: '출발지' })
  await pickupFieldset.getByPlaceholder('상세주소 (예: 층, 동/호수)').fill('3층 302호')
  const destinationFieldset = page.locator('fieldset', { hasText: '도착지' })
  await destinationFieldset.getByPlaceholder('상세주소 (예: 층, 동/호수)').fill('1층 로비')

  const senderFieldset = page.locator('fieldset', { hasText: '보내는 분' })
  await senderFieldset.getByPlaceholder('이름 *').fill(credentials.name)
  await senderFieldset.getByPlaceholder('연락처 *').fill(credentials.phoneNumber)

  const recipientFieldset = page.locator('fieldset', { hasText: '받는 분' })
  await recipientFieldset.getByPlaceholder('이름 *').fill('받는사람')
  await recipientFieldset.getByPlaceholder('연락처 *').fill(randomPhoneNumber())

  log('고객', '예상 요금 계산 대기')
  const submitButton = page.getByRole('button', { name: /결제하기$/ })
  await submitButton.waitFor({ state: 'visible' })
  await page.waitForFunction(() => {
    const button = [...document.querySelectorAll('button[type="submit"]')]
      .find((b) => b.textContent?.includes('결제하기'))
    return button && !button.disabled && !button.textContent?.includes('견적 전')
  }, { timeout: 20_000 })

  const fareText = await submitButton.innerText()
  log('고객', `배송요청 생성 (${fareText})`)
  await submitButton.click()
  await page.waitForURL(/\/customer\/deliveries\/\d+\/tracking$/, { timeout: 15_000 })
  const deliveryId = page.url().match(/deliveries\/(\d+)\/tracking/)?.[1]
  log('고객', `배송요청 생성 완료 (deliveryId=${deliveryId}) — 라이더를 찾고 있어요`)

  return { page, deliveryId }
}

async function runRider(context, credentials) {
  const page = await context.newPage()
  page.on('dialog', (dialog) => dialog.accept())
  if (STUB_KAKAO) {
    await page.route('**dapi.kakao.com/v2/maps/sdk.js**', (route) =>
      route.fulfill({ contentType: 'application/javascript', body: kakaoStubSource() }))
  }

  await page.goto(`${BASE_URL}/rider/signup`)
  await signup(page, '라이더', credentials)
  await page.goto(`${BASE_URL}/rider/login`)
  await login(page, '라이더', credentials)

  log('라이더', '운행 시작 ("퀵 시작하기")')
  await page.getByRole('button', { name: '퀵 시작하기' }).click()
  await page.waitForURL(/\/rider\/requests$/, { timeout: 10_000 })

  return page
}

async function acceptFirstCall(riderPage, expectedPickupAddress) {
  log('라이더', '콜 목록에서 새 콜 대기 중')
  let card = null
  for (let attempt = 0; attempt < 20; attempt++) {
    await riderPage.getByRole('button', { name: '새로 고침' }).click()
    await riderPage.waitForTimeout(500)
    const candidate = riderPage.getByRole('button', { name: new RegExp(expectedPickupAddress) })
    if (await candidate.count() > 0) {
      card = candidate.first()
      break
    }
    await riderPage.waitForTimeout(1000)
  }
  if (!card) {
    throw new Error('라이더 콜 목록에서 방금 만든 배송요청을 찾지 못했습니다.')
  }

  log('라이더', '콜 상세 화면으로 진입')
  await card.click()
  await riderPage.waitForURL(/\/rider\/requests\/\d+$/, { timeout: 10_000 })

  log('라이더', '콜 수락')
  await riderPage.getByRole('button', { name: '수락하기' }).click()
  await riderPage.waitForURL(/\/rider\/delivery$/, { timeout: 15_000 })
  await riderPage.locator('main[aria-label="진행 중 배송"]').waitFor({ timeout: 10_000 })
  log('라이더', '배차 확정 — 진행 중 배송 화면 진입')
}

async function main() {
  const browserArgsCustomer = ['--window-position=0,0', '--window-size=640,900']
  const browserArgsRider = ['--window-position=660,0', '--window-size=640,900']

  const [customerBrowser, riderBrowser] = await Promise.all([
    chromium.launch({ headless: HEADLESS, slowMo: SLOWMO, executablePath: EXECUTABLE_PATH, args: HEADLESS ? [] : browserArgsCustomer }),
    chromium.launch({ headless: HEADLESS, slowMo: SLOWMO, executablePath: EXECUTABLE_PATH, args: HEADLESS ? [] : browserArgsRider }),
  ])

  const customerContext = await customerBrowser.newContext({ viewport: { width: 420, height: 820 } })
  const riderContext = await riderBrowser.newContext({ viewport: { width: 420, height: 820 } })

  const suffix = uniqueSuffix()
  const customerCredentials = {
    loginId: `demo_cust_${suffix}`,
    password: 'Demo1234!',
    name: '데모고객',
    phoneNumber: randomPhoneNumber(),
  }
  const riderCredentials = {
    loginId: `demo_rider_${suffix}`,
    password: 'Demo1234!',
    name: '데모라이더',
    phoneNumber: randomPhoneNumber(),
  }

  try {
    const riderPage = await runRider(riderContext, riderCredentials)
    const { page: customerPage, deliveryId } = await runCustomer(customerContext, customerCredentials)

    await acceptFirstCall(riderPage, PICKUP_ADDRESS.roadAddress)

    log('고객', '라이더 배정 화면 갱신 대기')
    await customerPage.getByRole('heading', { name: '라이더가 배정됐어요' }).waitFor({ timeout: 20_000 })
    log('고객', `배차 완료 확인 (deliveryId=${deliveryId})`)

    console.log('\n=== 데모 성공: 고객 배송요청 -> 라이더 콜 수락 -> 배차 확정까지 재현 완료 ===\n')
  } catch (error) {
    console.error('\n=== 데모 실패 ===')
    console.error(error)
    process.exitCode = 1
  } finally {
    if (KEEP_OPEN && !process.exitCode) {
      log('시스템', '창을 열어 둡니다. 종료하려면 Ctrl+C를 누르세요.')
      await new Promise(() => {})
    } else {
      await customerBrowser.close()
      await riderBrowser.close()
    }
  }
}

main()
