// Turkey 퀵배송 매칭 — 고객(데스크톱 브라우저) + 라이더(안드로이드 에뮬레이터) + GPS 자동 재생 무인 데모
//
// demo.js 가 고객·라이더를 둘 다 데스크톱 크로미움으로 띄워 "배차 확정"까지만 재현하는 것과 달리,
// 이 스크립트는 라이더를 실제 안드로이드 앱(Capacitor WebView)으로 몰아 위치 전송 경로까지 재현한다:
//
//   1) 고객(크로미움)   : 회원가입 → 포인트 충전 → 배송요청 생성 → 추적 화면 대기
//   2) 라이더(에뮬 WebView, CDP 접속) : 회원가입 → 로그인 → 콜받기 → 그 콜 수락 → BUSY
//   3) 픽업 이동         : "픽업 출발하기" → `adb emu geo fix` 로 출발지(강남역)→픽업지 좌표 연속 주입
//                          → 네이티브 위치 서비스가 /api/rider/location 으로 POST → 고객 지도 마커 이동
//   4) 픽업지 도착       : "픽업 완료하기" → "배송 출발하기" (상태 전이는 위치 게이트 없이 버튼으로)
//   5) 배송 이동         : 픽업지→배송지(약 730m, 가까움)로 좌표 주입 → 실제 배송지 도착
//   6) 배송지 도착       : "배달인증하기" → 사진 첨부 → 완료 → COMPLETED(정산 생성, 라이더 AVAILABLE)
//   7) 완료 후 시연      : 실제 사용처럼 홈에서 버튼을 눌러 이동 — 포인트 내역(결제/정산) →
//                          배송 내역(목록 → 그 배송 상세). 경로 goto 없이 내비 버튼/링크를 클릭한다.
//
// 사전 조건(1단계 세팅이 끝나 있어야 한다 — 디스커션 #417 + 로컬 재빌드):
//   - 로컬 백엔드 기동:  localhost:8080  (에뮬레이터에서는 10.0.2.2:8080 으로 보인다)
//   - 고객 프런트 dev 서버:  BASE_URL(기본 http://localhost:5173)
//   - 에뮬레이터 부팅 + turkey 앱(com.example.app) 실행 중 (VITE_API_BASE_URL=http://10.0.2.2:8080 로 빌드된 것)
//   - 앱은 디버그 빌드라 WebView 원격 디버깅이 열려 있어야 한다(connectOverCDP 전제).
//
// 실행:
//   node demo-android.js
//
// 환경 변수:
//   BASE_URL     고객 프런트 주소 (기본 http://localhost:5173)
//   ADB          adb 실행 경로 (기본 ~/Library/Android/sdk/platform-tools/adb)
//   APP_ID       안드로이드 패키지명 (기본 com.example.app)
//   CDP_PORT     WebView CDP 포워딩 포트 (기본 9222)
//   SLOWMO       각 UI 액션 사이 지연(ms) (기본 650 — 회원가입·입력·화면 전환을 천천히)
//   KEEP_OPEN    true/false (기본 true — 끝나고도 고객 창을 열어 둔다. Ctrl+C 종료)
//   STEP_METERS  GPS 한 스텝 이동 거리(m) (기본 90 — 한 번에 크게 이동)
//   STEP_MS      GPS 스텝 간격(ms) (기본 2000). 속도 = STEP_METERS/STEP_MS*1000 = 45 m/s. 서버 상한 50 m/s.
//   STAGE_PAUSE_MS  상태 전이 후 새 화면 유지 시간(ms) (기본 1500)
//   PROOF_PHOTO  완료 인증 사진 경로 (미지정 시 스크립트 폴더의 proof.jpg/png, 그것도 없으면 더미 PNG)
//   POINTS_PAUSE_MS    완료 후 포인트 내역 화면 유지 시간(ms) (기본 2500)
//   SHOWCASE_PAUSE_MS  완료 후 배송 내역 상세 화면 유지 시간(ms) (기본 5000)
//   MAX_LEG_METERS  픽업지→배송지 구간 최대 재생 거리(m) (기본 0 = 전체 재생; 먼 좌표로 바꿀 때만 압축용)
//   SKIP_UI      true 면 UI 자동화를 건너뛰고, 이미 BUSY 인 라이더에 GPS 재생만 한다(피더 단독 반복 테스트용).

const { chromium } = require('playwright')
const { execFileSync } = require('child_process')
const fs = require('fs')
const path = require('path')

const HOME = process.env.HOME
const ADB = process.env.ADB || `${HOME}/Library/Android/sdk/platform-tools/adb`
const APP_ID = process.env.APP_ID || 'com.example.app'
const CDP_PORT = Number(process.env.CDP_PORT ?? 9222)
// 배포(CloudFront) 대상으로 돌린다. 로컬로 되돌리려면 BASE_URL=http://localhost:5173 로 실행.
const BASE_URL = process.env.BASE_URL || 'https://dw1nqa61d1no6.cloudfront.net'
// 배포 환경에선 인증번호(debugCode)를 응답에 주지 않아 자동 회원가입이 불가하다 → 로그인 전용.
// 미리 만들어 둔 배포 계정으로 로그인한다(회원가입 코드는 아래 runCustomer/runRiderOnDevice 에서 주석 처리).
// c1~c4 는 이미 진행 중 배송이 있어(고객당 진행 중 1건 제한) 새 요청 생성이 409로 막힌다.
// c5 는 진행 중 배송이 없어 사용 가능(확인함). 다른 계정을 쓰려면 CUSTOMER_LOGIN_ID 로 지정.
const CUSTOMER_LOGIN_ID = process.env.CUSTOMER_LOGIN_ID || 'c5'
const CUSTOMER_PASSWORD = process.env.CUSTOMER_PASSWORD || 'aa'
const RIDER_LOGIN_ID = process.env.RIDER_LOGIN_ID || 'roffline1'
const RIDER_PASSWORD = process.env.RIDER_PASSWORD || 'aa'
const SLOWMO = Number(process.env.SLOWMO ?? 650)
const KEEP_OPEN = process.env.KEEP_OPEN !== 'false'
// 라이더 이동: 한 스텝에 크게 이동(기본 90m), 속도 = 90/2000*1000 = 45 m/s (서버 상한 50 m/s 아래).
const STEP_METERS = Number(process.env.STEP_METERS ?? 90)
const STEP_MS = Number(process.env.STEP_MS ?? 2000)
// 상태 전이(픽업 출발/완료/배송 출발/완료)마다 새 화면을 잠깐 유지해 눈으로 따라오게 한다.
const STAGE_PAUSE_MS = Number(process.env.STAGE_PAUSE_MS ?? 1500)
// 완료 인증 사진. 지정 안 하면 스크립트 폴더의 proof.jpg/jpeg/png 를 찾고, 그것도 없으면 내장 더미 PNG.
const PROOF_PHOTO = process.env.PROOF_PHOTO || ''
// 완료 후 화면 유지 시간(ms). 포인트 내역은 조금 짧게, 배송 상세는 스크롤까지 봐야 하니 길게.
const POINTS_PAUSE_MS = Number(process.env.POINTS_PAUSE_MS ?? 2500)
const SHOWCASE_PAUSE_MS = Number(process.env.SHOWCASE_PAUSE_MS ?? 5000)
const SKIP_UI = process.env.SKIP_UI === 'true'
// 한 구간의 최대 이동 거리(m). 픽업지↔배송지가 가까워(약 730m) 전 구간을 재생해 실제로 도착한다.
// 0 이면 압축 없이 전체 재생. 멀리 떨어진 좌표로 바꿔 데모가 길어지면 이 값으로 잘라 압축할 수 있다.
const MAX_LEG_METERS = Number(process.env.MAX_LEG_METERS ?? 0)

// 고객 배송요청의 출발지/도착지 — demo.js 와 동일한 실제 서울 주소.
const PICKUP_ADDRESS = {
  zonecode: '06232',
  roadAddress: '서울 강남구 테헤란로 152',
  jibunAddress: '서울 강남구 역삼동 737',
  autoRoadAddress: '',
  autoJibunAddress: '',
  address: '서울 강남구 테헤란로 152',
}
// 좌표는 실제 Kakao geocoding 결과와 일치시킨다(고객이 이 주소를 geocode 해 저장하므로, 값이
// 어긋나면 라이더 마커가 지도 핀에서 떨어진 곳에 선다). 픽업/배송지를 가깝게 둬 라이더가 실제로 도착한다.
const PICKUP_COORDS = { latitude: 37.500024, longitude: 127.036509 } // 테헤란로 152 (geocode 확인)
const DESTINATION_ADDRESS = {
  zonecode: '06239',
  roadAddress: '서울 강남구 테헤란로 306',
  jibunAddress: '서울 강남구 역삼동 718',
  autoRoadAddress: '',
  autoJibunAddress: '',
  address: '서울 강남구 테헤란로 306',
}
const DESTINATION_COORDS = { latitude: 37.502595, longitude: 127.044102 } // 테헤란로 306 (픽업에서 약 730m, geocode 확인)

// 라이더 초기 위치: 픽업지에서 조금 떨어진 강남역(약 830m). 여기서 픽업지까지 이동을 보여준다.
const RIDER_START = { latitude: 37.4979, longitude: 127.0276 } // 강남역

// 두 구간. 상태 전이는 위치 게이트가 없어서, 각 구간을 재생한 뒤 "도착"으로 보고 버튼을 누른다.
// 실제 도로 폴리라인이 필요하면 각 배열에 경유 좌표를 더 넣으면 선분마다 STEP_METERS 간격으로 걷는다.
const ROUTE_TO_PICKUP = [RIDER_START, PICKUP_COORDS] // 출발 → 픽업지 (약 830m)
const ROUTE_TO_DESTINATION = [PICKUP_COORDS, DESTINATION_COORDS] // 픽업지 → 배송지 (약 730m, 전 구간 재생)

function log(actor, message) {
  const ts = new Date().toISOString().split('T')[1].replace('Z', '')
  console.log(`[${ts}] [${actor}] ${message}`)
}

function adb(args) {
  return execFileSync(ADB, args, { encoding: 'utf8' }).trim()
}

function uniqueSuffix() {
  return `${Date.now()}${Math.floor(Math.random() * 1000)}`
}

function randomPhoneNumber() {
  const rest = String(Math.floor(10000000 + Math.random() * 89999999))
  return `010${rest}`
}

// ── GPS 재생 ────────────────────────────────────────────────────────────────

function haversineMeters(a, b) {
  const R = 6371000
  const toRad = (d) => (d * Math.PI) / 180
  const dLat = toRad(b.latitude - a.latitude)
  const dLng = toRad(b.longitude - a.longitude)
  const lat1 = toRad(a.latitude)
  const lat2 = toRad(b.latitude)
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(h))
}

function interpolate(a, b, t) {
  return {
    latitude: a.latitude + (b.latitude - a.latitude) * t,
    longitude: a.longitude + (b.longitude - a.longitude) * t,
  }
}

// 폴리라인을 stepMeters 간격의 좌표 배열로 촘촘하게 채운다(선분별 등간격 walk).
function densify(waypoints, stepMeters) {
  const points = [waypoints[0]]
  for (let i = 1; i < waypoints.length; i++) {
    const from = waypoints[i - 1]
    const to = waypoints[i]
    const dist = haversineMeters(from, to)
    const steps = Math.max(1, Math.round(dist / stepMeters))
    for (let s = 1; s <= steps; s++) {
      points.push(interpolate(from, to, s / steps))
    }
  }
  return points
}

// 폴리라인을 시작점에서 maxMeters 만큼만 남기고 자른다(0/미지정이면 전체 유지).
function truncateRoute(waypoints, maxMeters) {
  if (!maxMeters) return waypoints
  const out = [waypoints[0]]
  let acc = 0
  for (let i = 1; i < waypoints.length; i++) {
    const seg = haversineMeters(waypoints[i - 1], waypoints[i])
    if (acc + seg <= maxMeters) {
      out.push(waypoints[i])
      acc += seg
    } else {
      out.push(interpolate(waypoints[i - 1], waypoints[i], (maxMeters - acc) / seg))
      break
    }
  }
  return out
}

function setGeo(point) {
  // adb emu geo fix 는 "경도 위도" 순서다(흔한 실수 지점).
  adb(['emu', 'geo', 'fix', String(point.longitude), String(point.latitude)])
}

async function playRoute(waypoints, arrivalLabel = '도착') {
  const points = densify(waypoints, STEP_METERS)
  const speed = (STEP_METERS / STEP_MS) * 1000
  log('GPS', `경로 재생 시작(${arrivalLabel} 방향) — ${points.length}개 지점, 스텝 ${STEP_METERS}m/${STEP_MS}ms (약 ${speed.toFixed(0)} m/s)`)
  if (speed > 50) {
    log('GPS', `⚠️ 속도 ${speed.toFixed(0)} m/s 가 서버 상한(50 m/s)을 넘습니다 — STEP_MS 를 늘리거나 STEP_METERS 를 줄이세요(전송이 거부됨).`)
  }
  for (let i = 0; i < points.length; i++) {
    setGeo(points[i])
    if (i % 5 === 0 || i === points.length - 1) {
      log('GPS', `(${i + 1}/${points.length}) ${points[i].latitude.toFixed(5)}, ${points[i].longitude.toFixed(5)}`)
    }
    await new Promise((r) => setTimeout(r, STEP_MS))
  }
  log('GPS', `경로 재생 완료 — ${arrivalLabel} 도착`)
}

// ── 고객 (데스크톱 크로미움) ──────────────────────────────────────────────────

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
  await page.waitForTimeout(500)
  await fillById(page, 'userId', loginId)
  await page.getByRole('button', { name: '중복 확인' }).click()
  await page.getByText('사용할 수 있는 아이디입니다.').waitFor({ timeout: 10_000 })

  await fillById(page, 'password', password)
  await fillById(page, 'passwordConfirm', password)
  await fillById(page, 'userName', name)
  await fillById(page, 'userPhone', phoneNumber)

  await page.getByRole('button', { name: '인증번호 전송' }).click()
  const debugCodeText = await page.locator('#signup-debug-code').innerText({ timeout: 10_000 })
  const code = debugCodeText.match(/(\d{6})/)?.[1]
  if (!code) {
    throw new Error(`${actor}: 로컬 테스트 인증번호를 읽지 못했습니다: ${debugCodeText}`)
  }
  log(actor, `인증번호 확인 (${code})`)
  await fillById(page, 'verificationCode', code)
  await page.getByRole('button', { name: '인증 확인' }).click()
  await page.getByText('휴대전화 인증이 완료되었습니다.').waitFor({ timeout: 10_000 })

  await page.getByRole('button', { name: '가입하기' }).click()
  await page.waitForURL(/\/(customer|rider)\/login/, { timeout: 10_000 })
  log(actor, '회원가입 완료')
}

async function login(page, actor, { loginId, password }) {
  log(actor, '로그인 시도')
  await page.waitForTimeout(500)
  await fillById(page, 'userId', loginId)
  await fillById(page, 'password', password)
  await page.getByRole('button', { name: '로그인' }).click()
  await page.waitForURL((url) => url.pathname === '/customer' || url.pathname === '/rider', { timeout: 10_000 })
  log(actor, '로그인 성공')
}

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

  // 배포 환경: 자동 회원가입 불가(인증번호 미노출) → 기존 계정 로그인. 로컬로 되돌릴 때 아래 2줄 주석 해제.
  // await page.goto(`${BASE_URL}/customer/signup`)
  // await signup(page, '고객', credentials)
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

// ── 라이더 (안드로이드 에뮬레이터 WebView, CDP 접속) ──────────────────────────

function riderAppPid() {
  const pid = adb(['shell', 'pidof', APP_ID])
  return pid ? pid.split(/\s+/)[0] : null
}

// 회원가입 등 입력 시 에뮬레이터에 뜨는 소프트 키보드/음성입력(IME)을 잠깐 끈다. Gboard 하나만 끄면
// 음성입력 IME로 폴백돼 패널이 뜨므로, 활성 IME를 전부 끈다(목록이 비면 아무 패널도 안 뜬다).
// Playwright fill 은 CDP 로 값을 넣어 IME 없이도 입력된다. 데모가 끝나면 finally 에서 복구한다.
function captureImeState() {
  try {
    const enabled = adb(['shell', 'ime', 'list', '-s'])
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean)
    const def = adb(['shell', 'settings', 'get', 'secure', 'default_input_method']).trim()
    return { enabled, def: def && def !== 'null' ? def : '' }
  } catch {
    return { enabled: [], def: '' }
  }
}

function disableAllImes(state) {
  for (const id of state.enabled) {
    try {
      adb(['shell', 'ime', 'disable', id])
    } catch {
      /* 개별 실패 무시 */
    }
  }
  if (state.enabled.length) {
    log('시스템', `소프트 키보드/음성입력(IME ${state.enabled.length}개) 비활성화`)
  }
}

function restoreImes(state) {
  for (const id of state.enabled) {
    try {
      adb(['shell', 'ime', 'enable', id])
    } catch {
      /* 개별 실패 무시 */
    }
  }
  if (state.def) {
    try {
      adb(['shell', 'ime', 'set', state.def])
    } catch {
      /* 복구 실패해도 사용자가 수동으로 되살릴 수 있음 */
    }
  }
}

async function connectRiderWebView() {
  const pid = riderAppPid()
  if (!pid) {
    throw new Error(`에뮬레이터에서 ${APP_ID} 앱이 실행 중이 아닙니다. 앱을 먼저 켜세요.`)
  }
  try {
    adb(['forward', '--remove', `tcp:${CDP_PORT}`])
  } catch {
    /* 포워딩이 없으면 무시 */
  }
  adb(['forward', `tcp:${CDP_PORT}`, `localabstract:webview_devtools_remote_${pid}`])
  log('라이더', `WebView CDP 접속 (pid=${pid}, port=${CDP_PORT})`)
  const browser = await chromium.connectOverCDP(`http://127.0.0.1:${CDP_PORT}`, { slowMo: SLOWMO })
  const context = browser.contexts()[0]
  const page = context.pages()[0] || (await context.waitForEvent('page'))
  page.on('dialog', (dialog) => dialog.accept())
  return { browser, page }
}

// WebView 는 데스크톱 브라우저 컨텍스트와 달리 세션(쿠키)·localStorage 가 실행 간에 남는다.
// 직전 run 의 로그인 세션이 남아 있으면 auth 가드가 /rider/signup 을 홈으로 돌려보내 회원가입 폼이
// 안 뜬다(#195 비인증 가드). 그래서 라이더 플로우 시작 전에 세션·역할 힌트를 비운다.
async function resetRiderSession(page) {
  await page.goto('https://localhost/')
  try {
    await page.evaluate(() => {
      window.localStorage.clear()
      window.sessionStorage.clear()
    })
  } catch {
    /* origin 접근 불가 시 무시 */
  }
  await page.context().clearCookies()
  log('라이더', '이전 세션·스토리지 정리 완료')
}

// WebView 안에서의 라이더 회원가입 → 로그인 → 콜받기(운행 시작). 앱은 https://localhost 에서
// 번들 SPA 로 서빙되므로 goto 로 라우트를 직접 연다.
async function runRiderOnDevice(page, credentials) {
  await resetRiderSession(page)
  // 배포 환경: 자동 회원가입 불가(인증번호 미노출) → 기존 계정 로그인. 로컬로 되돌릴 때 아래 2줄 주석 해제.
  // await page.goto('https://localhost/rider/signup')
  // await signup(page, '라이더', credentials)
  await page.goto('https://localhost/rider/login')
  await login(page, '라이더', credentials)

  // 로그인 후 홈(/rider) 렌더 대기. 라이더가 이미 AVAILABLE 이면 "퀵 시작하기"가 없고 "콜 목록 보기"만 있다.
  const startButton = page.getByRole('button', { name: '퀵 시작하기' })
  const callListButton = page.getByRole('button', { name: '콜 목록 보기' })
  await Promise.race([
    startButton.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {}),
    callListButton.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {}),
  ])

  if ((await startButton.count()) > 0) {
    log('라이더', '운행 시작 ("퀵 시작하기")')
    await startButton.click()
    await page.waitForURL(/\/rider\/requests$/, { timeout: 10_000 })
  } else {
    // 이미 AVAILABLE — 수락은 acceptByDeliveryId 가 상세 URL 로 직접 진입하므로 여기서 목록 이동은 불필요.
    log('라이더', '이미 운행 중(AVAILABLE) — "퀵 시작하기" 생략')
  }
}

// 고객이 방금 만든 "정확한 deliveryId" 상세로 직접 진입해 수락한다. 콜 목록에서 픽업주소로
// 매칭하면 반복 실행으로 쌓인 같은 주소의 이전/취소 주문 카드를 잘못 집어 "이미 배차/취소된 콜"
// 이 나므로(실측), 주소 매칭 + .first() 대신 id 로 못박는다. 생성 직후 조회 지연을 감안해 재시도한다.
async function acceptByDeliveryId(riderPage, deliveryId) {
  log('라이더', `콜 상세로 직접 이동 (deliveryId=${deliveryId})`)
  const detailUrl = `https://localhost/rider/requests/${deliveryId}`
  for (let attempt = 0; attempt < 10; attempt++) {
    await riderPage.goto(detailUrl)
    const acceptButton = riderPage.getByRole('button', { name: '수락하기' })
    try {
      await acceptButton.waitFor({ state: 'visible', timeout: 3000 })
    } catch {
      await riderPage.waitForTimeout(1000)
      continue
    }
    log('라이더', '콜 수락')
    await acceptButton.click()
    try {
      await riderPage.waitForURL(/\/rider\/delivery$/, { timeout: 8000 })
      await riderPage.locator('main[aria-label="진행 중 배송"]').waitFor({ timeout: 10_000 })
      log('라이더', '배차 확정 — 진행 중 배송(BUSY) 화면 진입, 네이티브 위치 서비스 시작됨')
      return
    } catch {
      // 이미 배차/취소 등으로 전이 실패 — 잠깐 쉬고 상태를 다시 본다.
      await riderPage.waitForTimeout(1000)
    }
  }
  throw new Error(`콜 수락 실패 (deliveryId=${deliveryId}) — 이미 배차/취소됐거나 상세가 뜨지 않았습니다.`)
}

// 진행 배송 화면(/rider/delivery)의 메인 액션 버튼을 누르고, 다음 단계 버튼이 뜰 때까지 기다린다.
// 전이는 버튼 클릭 → 낙관적 갱신으로 라벨이 다음 단계로 바뀐다. 상태별 라벨:
//   ASSIGNED "픽업 출발하기" → MOVING_TO_PICKUP "픽업 완료하기" → PICKED_UP "배송 출발하기"
//   → DELIVERING 은 "배달인증하기"(완료 인증 화면으로 이동).
async function tapDeliveryAction(riderPage, label, nextLabel) {
  const button = riderPage.getByRole('button', { name: label })
  await button.waitFor({ state: 'visible', timeout: 15_000 })
  log('라이더', `버튼 클릭: ${label}`)
  await button.click()
  if (nextLabel) {
    await riderPage.getByRole('button', { name: nextLabel }).waitFor({ state: 'visible', timeout: 15_000 })
  }
  // 새 단계 화면을 잠깐 유지(화면 전환을 눈으로 따라오게).
  await riderPage.waitForTimeout(STAGE_PAUSE_MS)
}

// 최소 유효 PNG(1x1). 완료 인증은 image/* · 비어있지 않음만 검증하므로 fallback 으로 충분하다.
// (로컬 S3Mock 미기동 시 서버가 proofUploadFailed=true 로 표시하지만 완료 자체는 정상 처리된다.)
const PROOF_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
)

// 완료 인증 사진 경로를 정한다: PROOF_PHOTO(지정 시 반드시 존재) → 스크립트 폴더의 proof.* → 없으면 null.
function resolveProofPhoto() {
  if (PROOF_PHOTO) {
    if (!fs.existsSync(PROOF_PHOTO)) {
      throw new Error(`PROOF_PHOTO 경로에 파일이 없습니다: ${PROOF_PHOTO}`)
    }
    return PROOF_PHOTO
  }
  for (const name of ['proof.jpg', 'proof.jpeg', 'proof.png']) {
    const candidate = path.join(__dirname, name)
    if (fs.existsSync(candidate)) return candidate
  }
  return null
}

// DELIVERING 상태에서 "배달인증하기" → 완료 화면 → 사진 첨부 → 인증 → /rider 홈으로.
// 사진 올리기까지 시간을 줄이려고, 전이 pause(STAGE_PAUSE) 대신 짧은 대기만 두고 바로 첨부한다.
async function completeDelivery(riderPage) {
  const authButton = riderPage.getByRole('button', { name: '배달인증하기' })
  await authButton.waitFor({ state: 'visible', timeout: 15_000 })
  log('라이더', '버튼 클릭: 배달인증하기')
  await authButton.click()
  await riderPage.waitForURL(/\/rider\/delivery\/\d+\/complete$/, { timeout: 10_000 })
  await riderPage.waitForTimeout(600) // 완료 화면 아주 잠깐만
  const proofPath = resolveProofPhoto()
  const fileInput = riderPage.locator('input[type="file"]')
  if (proofPath) {
    log('라이더', `완료 인증 사진 첨부: ${proofPath}`)
    await fileInput.setInputFiles(proofPath)
  } else {
    log('라이더', '완료 인증 사진 첨부: 내장 더미 PNG (PROOF_PHOTO 미지정 / proof.* 없음)')
    await fileInput.setInputFiles({ name: 'proof.png', mimeType: 'image/png', buffer: PROOF_PNG })
  }
  const confirm = riderPage.getByRole('button', { name: '인증하기' })
  await confirm.waitFor({ state: 'visible', timeout: 10_000 })
  log('라이더', '배송 완료 인증 제출')
  await confirm.click()
  await riderPage.waitForURL((url) => url.pathname === '/rider', { timeout: 15_000 })
  log('라이더', '배송 완료 — 라이더 홈 복귀(AVAILABLE)')
}

// 상세 화면이 뷰포트보다 길어 잘리므로, 한 번에 끝까지 부드럽게 스크롤해 전체 정보를 보여준다.
// window/body 스크롤이 아닌 별도 스크롤 컨테이너인 경우까지 대비해 가장 긴 스크롤 요소를 찾아 움직인다.
async function scrollToBottom(page) {
  await page.evaluate(() => {
    const scroller = [document.scrollingElement, ...document.querySelectorAll('*')]
      .filter(Boolean)
      .reduce((best, el) => (el.scrollHeight - el.clientHeight > (best ? best.scrollHeight - best.clientHeight : 0) ? el : best), null)
    if (scroller) scroller.scrollTo({ top: scroller.scrollHeight, behavior: 'smooth' })
  }).catch(() => {})
}
// 완료 후 시연: 실제 사용처럼 홈 화면에서 버튼을 눌러 (1) 포인트 내역, (2) 배송 내역 상세로 이동한다.
// 경로를 goto 로 바로 열지 않고, 각 화면의 실제 내비게이션 버튼/링크를 클릭한다.
async function showcaseAfterDelivery(customerPage, riderPage, deliveryId) {
  // 라이더는 완료 후 이미 홈(/rider). 고객은 추적(완료) 화면 헤더의 홈 링크로 홈으로 돌아간다.
  log('시연', '고객: 추적 화면에서 홈으로')
  await customerPage.getByRole('link', { name: 'Home' }).click()
  await customerPage.waitForURL((url) => url.pathname === '/customer', { timeout: 10_000 })
  await customerPage.waitForTimeout(STAGE_PAUSE_MS)

  // 1) 포인트 내역 — 홈에서 버튼 클릭 (고객 "포인트 기록보기" / 라이더 "포인트")
  log('시연', '포인트 내역 — 홈에서 버튼 클릭 (고객 결제 / 라이더 정산)')
  await Promise.all([
    (async () => {
      await customerPage.getByRole('link', { name: '포인트 기록보기' }).click()
      await customerPage.waitForURL(/\/customer\/points$/, { timeout: 10_000 })
    })(),
    (async () => {
      await riderPage.getByRole('button', { name: '포인트' }).click()
      await riderPage.waitForURL(/\/rider\/points$/, { timeout: 10_000 })
    })(),
  ])
  await customerPage.waitForTimeout(POINTS_PAUSE_MS)

  // 2) 배송 내역 상세 — 홈으로 돌아가 목록으로 들어가 그 배송 항목을 연다.
  log('시연', '배송 내역 상세 — 홈 → 목록 → 방금 그 배송')
  await Promise.all([
    (async () => {
      // 고객: 포인트 뒤로 → 홈 → 설정 → 배송 내역 → 상세 보기
      await customerPage.getByRole('button', { name: '고객 홈으로 돌아가기' }).click()
      await customerPage.waitForURL((url) => url.pathname === '/customer', { timeout: 10_000 })
      await customerPage.getByRole('link', { name: '설정' }).click()
      await customerPage.waitForURL(/\/account\/settings$/, { timeout: 10_000 })
      await customerPage.getByRole('link', { name: '배송 내역' }).click()
      await customerPage.waitForURL(/\/customer\/deliveries$/, { timeout: 10_000 })
      await customerPage.getByRole('link', { name: '상세 보기' }).first().click()
      await customerPage.waitForURL(/\/customer\/deliveries\/\d+$/, { timeout: 10_000 })
      await customerPage.waitForTimeout(STAGE_PAUSE_MS) // 상단 잠깐 → 아래로 스크롤해 전체 정보 노출
      await scrollToBottom(customerPage)
    })(),
    (async () => {
      // 라이더: 포인트 뒤로 → 홈 → 배송 기록 → 그 주문 항목
      await riderPage.getByRole('button', { name: '라이더 홈으로 돌아가기' }).click()
      await riderPage.waitForURL((url) => url.pathname === '/rider', { timeout: 10_000 })
      await riderPage.getByRole('button', { name: '배송 기록' }).click()
      await riderPage.waitForURL(/\/rider\/history$/, { timeout: 10_000 })
      await riderPage.getByRole('button', { name: `주문 #${deliveryId}` }).click()
      await riderPage.waitForURL(/\/rider\/history\/\d+$/, { timeout: 10_000 })
      await riderPage.waitForTimeout(STAGE_PAUSE_MS) // 상단 잠깐 → 아래로 스크롤해 전체 정보 노출
      await scrollToBottom(riderPage)
    })(),
  ])
  await customerPage.waitForTimeout(SHOWCASE_PAUSE_MS)
}

// ── 사전 점검 ────────────────────────────────────────────────────────────────

async function preflight() {
  // adb / 에뮬레이터
  const devices = adb(['devices'])
  if (!/\bdevice\b/.test(devices.replace('List of devices attached', ''))) {
    throw new Error('연결된 에뮬레이터/기기가 없습니다. `adb devices` 로 확인하세요.')
  }
  if (!SKIP_UI && !riderAppPid()) {
    throw new Error(`${APP_ID} 앱이 실행 중이 아닙니다. 에뮬레이터에서 앱을 먼저 켜세요.`)
  }
  // 백엔드 헬스체크(대상 = BASE_URL 오리진의 /api/health). 실패해도 치명적이지 않으니 경고만.
  try {
    const res = await fetch(`${BASE_URL}/api/health`)
    if (!res.ok) throw new Error(String(res.status))
    log('시스템', `백엔드 헬스 OK (${BASE_URL})`)
  } catch (e) {
    log('시스템', `⚠️ 백엔드 헬스체크 실패(${BASE_URL}/api/health): ${e.message} — 계속 진행`)
  }
  // 고객 프런트 접속 확인
  if (!SKIP_UI) {
    try {
      await fetch(BASE_URL)
    } catch (e) {
      throw new Error(`고객 프런트(${BASE_URL})에 접속할 수 없습니다: ${e.message}`)
    }
  }
  log('시스템', '사전 점검 통과')
}

async function main() {
  await preflight()

  // 피더 단독 반복 테스트 모드: 이미 BUSY 인 라이더에 경로만 재생.
  if (SKIP_UI) {
    log('시스템', 'SKIP_UI=true — UI 자동화를 건너뛰고 GPS 경로 재생만 수행합니다.')
    setGeo(RIDER_START)
    await playRoute(ROUTE_TO_PICKUP, '픽업지')
    await playRoute(truncateRoute(ROUTE_TO_DESTINATION, MAX_LEG_METERS), '배송지')
    return
  }

  const customerBrowser = await chromium.launch({
    headless: false,
    slowMo: SLOWMO,
    args: ['--window-position=0,0', '--window-size=640,900'],
  })
  const customerContext = await customerBrowser.newContext({ viewport: { width: 420, height: 820 } })

  // 배포 계정으로 로그인만 한다(회원가입 주석 처리). name/phoneNumber 는 로그인 경로에선 쓰이지 않는다.
  const customerCredentials = {
    loginId: CUSTOMER_LOGIN_ID,
    password: CUSTOMER_PASSWORD,
    name: '데모고객',
    phoneNumber: randomPhoneNumber(),
  }
  const riderCredentials = {
    loginId: RIDER_LOGIN_ID,
    password: RIDER_PASSWORD,
    name: '데모라이더',
    phoneNumber: randomPhoneNumber(),
  }

  let riderBrowser
  // 회원가입/로그인 입력 중 에뮬레이터에 소프트 키보드/음성입력이 뜨지 않게 활성 IME를 전부 끈다(finally 복구).
  const imeState = captureImeState()
  disableAllImes(imeState)
  try {
    // 라이더를 출발 지점(픽업지에서 조금 떨어진 강남역)에 위치시켜 둔다.
    setGeo(RIDER_START)

    const { browser, page: riderPage } = await connectRiderWebView()
    riderBrowser = browser
    await runRiderOnDevice(riderPage, riderCredentials)

    const { page: customerPage, deliveryId } = await runCustomer(customerContext, customerCredentials)

    await acceptByDeliveryId(riderPage, deliveryId)

    log('고객', '라이더 배정 화면 갱신 대기')
    await customerPage.getByRole('heading', { name: '라이더가 배정됐어요' }).waitFor({ timeout: 20_000 })
    log('고객', `배차 완료 확인 (deliveryId=${deliveryId})`)

    // 1) ASSIGNED → (살짝 텀) → 픽업 출발 → 픽업지까지 이동
    await riderPage.waitForTimeout(1500) // "픽업 출발하기"를 조금 천천히 누르게
    await tapDeliveryAction(riderPage, '픽업 출발하기', '픽업 완료하기')
    await playRoute(ROUTE_TO_PICKUP, '픽업지')

    // 2) 픽업지 도착 → 픽업 완료 → (살짝 텀) → 배송 출발
    await tapDeliveryAction(riderPage, '픽업 완료하기', '배송 출발하기')
    await riderPage.waitForTimeout(1500) // "배송 출발하기"를 조금 천천히 누르게
    await tapDeliveryAction(riderPage, '배송 출발하기', '배달인증하기')

    // 3) 배송지까지 이동(가까워 전 구간 재생 — 실제 도착)
    await playRoute(truncateRoute(ROUTE_TO_DESTINATION, MAX_LEG_METERS), '배송지')

    // 4) 배송지 도착 → 완료 인증(사진) → COMPLETED
    await completeDelivery(riderPage)

    // 5) 완료 후 시연: 포인트 내역(결제/정산) → 배송 내역 상세
    await showcaseAfterDelivery(customerPage, riderPage, deliveryId)

    console.log('\n=== 데모 성공: 배송요청 -> 수락 -> 픽업/배송 이동 -> 완료 -> 포인트·배송 내역 시연까지 완료 ===\n')
  } catch (error) {
    console.error('\n=== 데모 실패 ===')
    console.error(error)
    process.exitCode = 1
  } finally {
    // 소프트 키보드/음성입력(IME) 복구.
    restoreImes(imeState)
    // CDP 로 붙은 라이더 브라우저는 disconnect 만 한다(앱은 그대로 유지).
    if (riderBrowser) await riderBrowser.close()
    if (KEEP_OPEN && !process.exitCode) {
      log('시스템', '고객 창을 열어 둡니다. 종료하려면 Ctrl+C를 누르세요.')
      await new Promise(() => {})
    } else {
      await customerBrowser.close()
    }
  }
}

main()
