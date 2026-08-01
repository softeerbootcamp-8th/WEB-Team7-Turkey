import { describe, expect, it } from 'vitest'
import {
  normalizePathname,
  resolveAccountGuard,
  resolveCustomerGuard,
  resolveGuestGuard,
  resolveLandingGuard,
  resolveRiderGuard,
  type SessionInfo,
} from './guard'
import { validateRedirectSearch } from './redirectSearch'

const guest: SessionInfo = { role: null }
const customer: SessionInfo = { role: 'CUSTOMER', customer: { memberId: 1, loginId: 'c1', name: '고객' } }

function rider(operatingStatus?: 'UNAVAILABLE' | 'AVAILABLE' | 'BUSY'): SessionInfo {
  return { role: 'RIDER', rider: { memberId: 2, loginId: 'r1', name: '라이더', operatingStatus } }
}

describe('resolveCustomerGuard', () => {
  it('비로그인이면 고객 로그인 화면으로 보내고 원래 목적지를 보존한다', () => {
    expect(resolveCustomerGuard(guest, '/customer/deliveries?page=2')).toEqual({
      action: 'login-required',
      to: '/customer/login',
      redirectParam: '/customer/deliveries?page=2',
    })
  })

  it('라이더가 고객 화면에 들어오면 로그인 화면이 아니라 라이더 홈으로 보낸다', () => {
    // 로그인은 돼 있으므로 로그인 화면으로 보내면 "왜 또 로그인하라는지" 알 수 없다.
    expect(resolveCustomerGuard(rider('AVAILABLE'), '/customer/points')).toEqual({
      action: 'redirect',
      to: '/rider',
    })
  })

  it('고객 세션이면 통과시킨다', () => {
    expect(resolveCustomerGuard(customer, '/customer/deliveries')).toEqual({ action: 'allow' })
  })
})

describe('resolveRiderGuard - 인증·역할', () => {
  it('비로그인이면 라이더 로그인 화면으로 보내고 목적지를 보존한다', () => {
    expect(resolveRiderGuard(guest, '/rider/delivery', '/rider/delivery')).toEqual({
      action: 'login-required',
      to: '/rider/login',
      redirectParam: '/rider/delivery',
    })
  })

  it('고객이 라이더 화면에 들어오면 고객 홈으로 보낸다', () => {
    expect(resolveRiderGuard(customer, '/rider/requests', '/rider/requests')).toEqual({
      action: 'redirect',
      to: '/customer',
    })
  })

  it('서버가 운행 상태를 주지 않으면 상태 정합성을 따지지 않고 통과시킨다', () => {
    expect(resolveRiderGuard(rider(undefined), '/rider/requests', '/rider/requests')).toEqual({ action: 'allow' })
  })
})

describe('resolveRiderGuard - 운행 상태 ↔ 화면 정합성', () => {
  it.each([
    ['BUSY', '/rider', '/rider/delivery'],
    ['BUSY', '/rider/requests', '/rider/delivery'],
    ['AVAILABLE', '/rider', '/rider/requests'],
    ['AVAILABLE', '/rider/delivery', '/rider/requests'],
    ['UNAVAILABLE', '/rider/requests', '/rider'],
    ['UNAVAILABLE', '/rider/delivery', '/rider'],
  ] as const)('%s 상태로 %s 에 들어가면 %s 로 보낸다', (status, pathname, expected) => {
    expect(resolveRiderGuard(rider(status), pathname, pathname)).toEqual({ action: 'redirect', to: expected })
  })

  it.each([
    ['BUSY', '/rider/delivery'],
    ['AVAILABLE', '/rider/requests'],
    ['UNAVAILABLE', '/rider'],
  ] as const)('%s 상태가 이미 %s 에 있으면 통과시킨다', (status, pathname) => {
    expect(resolveRiderGuard(rider(status), pathname, pathname)).toEqual({ action: 'allow' })
  })

  it.each([
    ['/rider/history'],
    ['/rider/history/12'],
    ['/rider/points'],
    ['/rider/requests/12'],
  ])('강제 대상이 아닌 %s 는 상태와 무관하게 열린다', (pathname) => {
    // BUSY 라이더가 정산·이력을 못 보게 되는 것을 막는 회귀 테스트.
    // 상태 매핑은 홈/콜목록/진행배송 3개 화면끼리만 적용한다.
    expect(resolveRiderGuard(rider('BUSY'), pathname, pathname)).toEqual({ action: 'allow' })
    expect(resolveRiderGuard(rider('UNAVAILABLE'), pathname, pathname)).toEqual({ action: 'allow' })
  })

  it('경로 끝의 슬래시는 같은 화면으로 본다', () => {
    expect(resolveRiderGuard(rider('BUSY'), '/rider/delivery/', '/rider/delivery/')).toEqual({ action: 'allow' })
    expect(resolveRiderGuard(rider('AVAILABLE'), '/rider/', '/rider/')).toEqual({
      action: 'redirect',
      to: '/rider/requests',
    })
  })
})

describe('resolveGuestGuard', () => {
  it('로그인 상태로 비인증 화면에 오면 역할별 홈으로 보낸다', () => {
    expect(resolveGuestGuard(customer)).toEqual({ action: 'redirect', to: '/customer' })
    expect(resolveGuestGuard(rider('BUSY'))).toEqual({ action: 'redirect', to: '/rider' })
  })

  it('비로그인이면 통과시킨다', () => {
    expect(resolveGuestGuard(guest)).toEqual({ action: 'allow' })
  })
})

describe('resolveLandingGuard', () => {
  it('비로그인이면 랜딩 화면을 표시한다', () => {
    expect(resolveLandingGuard(guest)).toEqual({ action: 'allow' })
  })

  it('고객은 고객 홈으로 보낸다', () => {
    expect(resolveLandingGuard(customer)).toEqual({ action: 'redirect', to: '/customer' })
  })

  it.each([
    ['UNAVAILABLE', '/rider'],
    ['AVAILABLE', '/rider/requests'],
    ['BUSY', '/rider/delivery'],
  ] as const)('%s 라이더는 %s 로 보낸다', (status, expected) => {
    expect(resolveLandingGuard(rider(status))).toEqual({ action: 'redirect', to: expected })
  })

  it('운행 상태가 없는 라이더는 라이더 홈으로 보낸다', () => {
    expect(resolveLandingGuard(rider())).toEqual({ action: 'redirect', to: '/rider' })
  })
})

describe('resolveAccountGuard', () => {
  it('비로그인이면 역할 선택 랜딩으로 보내고 목적지를 보존한다', () => {
    // account/* 는 고객·라이더 공용이라 어느 역할의 로그인 화면을 골라야 할지 알 수 없다.
    expect(resolveAccountGuard(guest, '/account/settings')).toEqual({
      action: 'login-required',
      to: '/',
      redirectParam: '/account/settings',
    })
  })

  it('역할과 무관하게 로그인만 되어 있으면 통과시킨다', () => {
    expect(resolveAccountGuard(customer, '/account')).toEqual({ action: 'allow' })
    expect(resolveAccountGuard(rider('UNAVAILABLE'), '/account')).toEqual({ action: 'allow' })
  })
})

describe('normalizePathname', () => {
  it('끝의 슬래시만 떼고 루트는 그대로 둔다', () => {
    expect(normalizePathname('/rider/')).toBe('/rider')
    expect(normalizePathname('/rider')).toBe('/rider')
    expect(normalizePathname('/')).toBe('/')
  })
})

describe('validateRedirectSearch', () => {
  it('같은 출처의 절대 경로만 통과시킨다', () => {
    expect(validateRedirectSearch({ redirect: '/customer/deliveries?page=2' })).toEqual({
      redirect: '/customer/deliveries?page=2',
    })
  })

  it('외부 출처로 나가는 값은 버린다', () => {
    // 주소창으로 들어오는 값이라 그대로 믿으면 오픈 리다이렉트가 된다.
    expect(validateRedirectSearch({ redirect: 'https://evil.example/login' })).toEqual({})
    expect(validateRedirectSearch({ redirect: '//evil.example' })).toEqual({})
    expect(validateRedirectSearch({ redirect: 'customer/deliveries' })).toEqual({})
  })

  it('문자열이 아니거나 없으면 버린다', () => {
    expect(validateRedirectSearch({})).toEqual({})
    expect(validateRedirectSearch({ redirect: 42 })).toEqual({})
  })
})
