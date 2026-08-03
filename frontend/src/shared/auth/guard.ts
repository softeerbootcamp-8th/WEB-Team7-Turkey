import type { CustomerSessionResponse, RiderSessionResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

/**
 * 라우트 가드의 판정 로직. 라우터·네트워크에 의존하지 않는 순수 함수로 두고,
 * 라우트 파일은 결과를 받아 redirect 를 던지는 일만 한다(분기가 많아 테스트로 고정할 가치가 있다).
 *
 * 인가는 프론트 가드가 1차, 서버가 2차다(CLAUDE.md). 여기서 막는 것은 UX 이고 실제 보호는
 * 서버 세션 인터셉터가 한다 — 가드를 우회해도 API 가 401 을 낸다.
 */

/** 역할별 세션 확인 결과를 합성한 값. `role: null` 은 "어느 역할로도 로그인되지 않음"이다. */
export type SessionInfo =
  | { role: 'CUSTOMER'; customer: CustomerSessionResponse }
  | { role: 'RIDER'; rider: RiderSessionResponse }
  | { role: null }

export const CUSTOMER_HOME = '/customer'
export const RIDER_HOME = '/rider'
export const CUSTOMER_LOGIN = '/customer/login'
export const RIDER_LOGIN = '/rider/login'
/** 역할을 모르는 채 로그인이 필요할 때(account/*) 보낼 곳. 역할 선택은 랜딩이 담당한다. */
export const ROLE_NEUTRAL_LANDING = '/'

/** 라이더 운행 상태 ↔ 상태별 업무 화면. 라이더 홈은 모든 상태에서 열 수 있다. */
export const RIDER_STATUS_ROUTE = {
  UNAVAILABLE: '/rider',
  AVAILABLE: '/rider/requests',
  BUSY: '/rider/delivery',
} as const

/**
 * 상태 정합성을 강제하는 화면 집합.
 *
 * 콜 목록과 진행 배송 화면에만 상태 정합성을 적용한다. 홈은 퀵 시작·종료를 제어하는
 * 대시보드이므로 모든 상태에서 열 수 있고, 이력·정산·콜 상세도 상태와 무관하게 열린다.
 */
const STATUS_ENFORCED_ROUTES: ReadonlySet<string> = new Set([
  RIDER_STATUS_ROUTE.AVAILABLE,
  RIDER_STATUS_ROUTE.BUSY,
])

/** 원래 목적지를 `?redirect=` 로 보존할 수 있는(= validateSearch 가 걸린) 라우트만 온다. */
export type LoginRedirectTarget = typeof CUSTOMER_LOGIN | typeof RIDER_LOGIN | typeof ROLE_NEUTRAL_LANDING

/** search 파라미터 없이 그대로 보내는 라우트. */
export type PlainRedirectTarget =
  | typeof CUSTOMER_HOME
  | typeof RIDER_HOME
  | (typeof RIDER_STATUS_ROUTE)[keyof typeof RIDER_STATUS_ROUTE]

/**
 * 두 리다이렉트를 별도 action 으로 나눈 이유: 타입이 붙은 라우터에서 `redirect({to, search})` 의
 * search 는 대상 라우트가 선언한 것만 허용된다. 하나로 합치면 search 를 받지 않는 대상까지
 * 같은 분기에 섞여 캐스팅이 필요해진다.
 */
export type GuardDecision =
  | { action: 'allow' }
  | { action: 'redirect'; to: PlainRedirectTarget }
  | { action: 'login-required'; to: LoginRedirectTarget; redirectParam: string }

/** `/rider/` 와 `/rider` 를 같은 경로로 본다. 루트('/')는 그대로 둔다. */
export function normalizePathname(pathname: string): string {
  if (pathname.length > 1 && pathname.endsWith('/')) {
    return pathname.slice(0, -1)
  }
  return pathname
}

/** 고객 전용 라우트(`customer/_authed`) 가드. */
export function resolveCustomerGuard(session: SessionInfo, href: string): GuardDecision {
  if (session.role === null) {
    return { action: 'login-required', to: CUSTOMER_LOGIN, redirectParam: href }
  }
  // 라이더가 고객 화면에 들어온 경우. 로그인은 돼 있으니 로그인 화면이 아니라 자기 역할 홈으로 보낸다.
  if (session.role === 'RIDER') {
    return { action: 'redirect', to: RIDER_HOME }
  }
  return { action: 'allow' }
}

/** 라이더 전용 라우트(`rider/_authed`) 가드. 역할 확인 후 운행 상태와 화면이 맞는지 본다. */
export function resolveRiderGuard(session: SessionInfo, href: string, pathname: string): GuardDecision {
  if (session.role === null) {
    return { action: 'login-required', to: RIDER_LOGIN, redirectParam: href }
  }
  if (session.role === 'CUSTOMER') {
    return { action: 'redirect', to: CUSTOMER_HOME }
  }

  const status = session.rider.operatingStatus
  // 서버가 상태를 안 준 경우(스키마상 optional)는 정합성을 판단할 근거가 없으므로 통과시킨다.
  if (!status) {
    return { action: 'allow' }
  }

  const current = normalizePathname(pathname)
  const expected = RIDER_STATUS_ROUTE[status]
  if (STATUS_ENFORCED_ROUTES.has(current) && current !== expected) {
    return { action: 'redirect', to: expected }
  }
  return { action: 'allow' }
}

/** 비인증 가드(`auth/*`). 이미 로그인한 사용자는 역할별 홈으로 보낸다. */
export function resolveGuestGuard(session: SessionInfo): GuardDecision {
  if (session.role === 'CUSTOMER') {
    return { action: 'redirect', to: CUSTOMER_HOME }
  }
  if (session.role === 'RIDER') {
    return { action: 'redirect', to: RIDER_HOME }
  }
  return { action: 'allow' }
}

/** 루트 랜딩(`/`) 진입. 로그인 상태라면 역할별 홈으로 보낸다. */
export function resolveLandingGuard(session: SessionInfo): GuardDecision {
  if (session.role === 'CUSTOMER') {
    return { action: 'redirect', to: CUSTOMER_HOME }
  }
  if (session.role === 'RIDER') {
    return { action: 'redirect', to: RIDER_HOME }
  }
  return { action: 'allow' }
}

/** 역할 무관 인증 가드(`account/*`). 고객·라이더 공용 화면이라 역할은 따지지 않는다. */
export function resolveAccountGuard(session: SessionInfo, href: string): GuardDecision {
  if (session.role === null) {
    return { action: 'login-required', to: ROLE_NEUTRAL_LANDING, redirectParam: href }
  }
  return { action: 'allow' }
}
