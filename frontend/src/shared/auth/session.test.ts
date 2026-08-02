import { QueryClient } from '@tanstack/react-query'
import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const getCustomerSession = vi.fn()
const getRiderSession = vi.fn()
const roleState = vi.hoisted(() => ({ value: null as 'CUSTOMER' | 'RIDER' | null }))

vi.mock('@/api/generated/customer-session/customer-session', () => ({
  getCustomerSession: (...args: unknown[]) => getCustomerSession(...args),
  getGetCustomerSessionQueryKey: () => ['/api/customer/session'],
}))

vi.mock('@/api/generated/rider-session/rider-session', () => ({
  getRiderSession: (...args: unknown[]) => getRiderSession(...args),
  getGetRiderSessionQueryKey: () => ['/api/rider/session'],
}))

vi.mock('./sessionRole', () => ({
  readStoredSessionRole: () => roleState.value,
  storeSessionRole: (role: 'CUSTOMER' | 'RIDER') => {
    roleState.value = role
  },
  clearStoredSessionRole: () => {
    roleState.value = null
  },
}))

const {
  ensureSessionInfo,
  clearSessionQueries,
  cacheAuthenticatedCustomer,
  cacheAuthenticatedRider,
  isUnauthorized,
} = await import('./session')

function httpError(status: number): AxiosError {
  const response = { status, data: null, statusText: '', headers: {}, config: {} } as unknown as AxiosResponse
  return new AxiosError('http error', String(status), { headers: new AxiosHeaders() }, undefined, response)
}

function networkError(): AxiosError {
  return new AxiosError('Network Error', AxiosError.ERR_NETWORK, { headers: new AxiosHeaders() })
}

let queryClient: QueryClient

beforeEach(() => {
  vi.clearAllMocks()
  roleState.value = null
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
})

describe('ensureSessionInfo - 저장된 역할에 따른 단일 조회', () => {
  it('저장된 역할이 없으면 어느 세션 API도 호출하지 않는다', async () => {
    await expect(ensureSessionInfo(queryClient)).resolves.toEqual({ role: null })

    expect(getCustomerSession).not.toHaveBeenCalled()
    expect(getRiderSession).not.toHaveBeenCalled()
  })

  it('CUSTOMER 역할이면 고객 세션만 조회한다', async () => {
    roleState.value = 'CUSTOMER'
    getCustomerSession.mockResolvedValue({
      success: true,
      data: { memberId: 1, loginId: 'c1', name: '고객' },
    })

    await expect(ensureSessionInfo(queryClient)).resolves.toEqual({
      role: 'CUSTOMER',
      customer: { memberId: 1, loginId: 'c1', name: '고객' },
    })
    expect(getCustomerSession).toHaveBeenCalledTimes(1)
    expect(getRiderSession).not.toHaveBeenCalled()
  })

  it('RIDER 역할이면 라이더 세션만 조회한다', async () => {
    roleState.value = 'RIDER'
    getRiderSession.mockResolvedValue({
      success: true,
      data: { memberId: 2, loginId: 'r1', name: '라이더', operatingStatus: 'BUSY' },
    })

    await expect(ensureSessionInfo(queryClient)).resolves.toEqual({
      role: 'RIDER',
      rider: { memberId: 2, loginId: 'r1', name: '라이더', operatingStatus: 'BUSY' },
    })
    expect(getCustomerSession).not.toHaveBeenCalled()
    expect(getRiderSession).toHaveBeenCalledTimes(1)
  })

  it('선택한 세션 API가 401이면 역할을 제거하고 비로그인으로 판정한다', async () => {
    roleState.value = 'CUSTOMER'
    getCustomerSession.mockRejectedValue(httpError(401))

    await expect(ensureSessionInfo(queryClient)).resolves.toEqual({ role: null })
    expect(roleState.value).toBeNull()
    expect(getRiderSession).not.toHaveBeenCalled()
  })
})

describe('ensureSessionInfo - 실패를 만료로 오해하지 않는다', () => {
  it('선택한 API의 5xx는 비로그인이 아니라 오류를 올린다', async () => {
    roleState.value = 'CUSTOMER'
    getCustomerSession.mockRejectedValue(httpError(500))

    await expect(ensureSessionInfo(queryClient)).rejects.toMatchObject({ response: { status: 500 } })
    expect(roleState.value).toBe('CUSTOMER')
  })

  it('네트워크 오류도 비로그인이 아니라 오류를 올린다', async () => {
    roleState.value = 'RIDER'
    getRiderSession.mockRejectedValue(networkError())

    await expect(ensureSessionInfo(queryClient)).rejects.toMatchObject({ code: AxiosError.ERR_NETWORK })
    expect(roleState.value).toBe('RIDER')
  })

  it('200인데 body에 data가 없으면 오류를 올린다', async () => {
    roleState.value = 'CUSTOMER'
    getCustomerSession.mockResolvedValue({ success: true })

    await expect(ensureSessionInfo(queryClient)).rejects.toThrow(/data 가 없습니다/)
  })
})

describe('ensureSessionInfo - 로그인 캐시와 역할 저장', () => {
  it('고객 로그인 응답은 CUSTOMER 역할과 고객 세션을 캐시한다', async () => {
    const customer = { memberId: 1, loginId: 'c1', name: '고객' }

    cacheAuthenticatedCustomer(queryClient, customer)

    expect(roleState.value).toBe('CUSTOMER')
    await expect(ensureSessionInfo(queryClient)).resolves.toEqual({ role: 'CUSTOMER', customer })
    expect(getCustomerSession).not.toHaveBeenCalled()
    expect(getRiderSession).not.toHaveBeenCalled()
  })

  it('라이더 로그인 응답은 RIDER 역할과 라이더 세션을 캐시한다', async () => {
    const rider = { memberId: 2, loginId: 'r1', name: '라이더', operatingStatus: 'AVAILABLE' as const }

    cacheAuthenticatedRider(queryClient, rider)

    expect(roleState.value).toBe('RIDER')
    await expect(ensureSessionInfo(queryClient)).resolves.toEqual({ role: 'RIDER', rider })
    expect(getCustomerSession).not.toHaveBeenCalled()
    expect(getRiderSession).not.toHaveBeenCalled()
  })

  it('신선한 세션 캐시가 있으면 선택한 API도 다시 부르지 않는다', async () => {
    roleState.value = 'CUSTOMER'
    getCustomerSession.mockResolvedValue({
      success: true,
      data: { memberId: 1, loginId: 'c1', name: '고객' },
    })

    await ensureSessionInfo(queryClient)
    await ensureSessionInfo(queryClient)

    expect(getCustomerSession).toHaveBeenCalledTimes(1)
    expect(getRiderSession).not.toHaveBeenCalled()
  })

  it('세션 정리 시 역할 힌트와 두 역할 캐시를 함께 제거한다', async () => {
    const customer = { memberId: 1, loginId: 'c1', name: '고객' }
    cacheAuthenticatedCustomer(queryClient, customer)

    clearSessionQueries(queryClient)

    expect(roleState.value).toBeNull()
    await expect(ensureSessionInfo(queryClient)).resolves.toEqual({ role: null })
    expect(getCustomerSession).not.toHaveBeenCalled()
    expect(getRiderSession).not.toHaveBeenCalled()
  })
})

describe('isUnauthorized', () => {
  it('401만 참으로 본다', () => {
    expect(isUnauthorized(httpError(401))).toBe(true)
    expect(isUnauthorized(httpError(403))).toBe(false)
    expect(isUnauthorized(httpError(500))).toBe(false)
    expect(isUnauthorized(networkError())).toBe(false)
    expect(isUnauthorized(new Error('boom'))).toBe(false)
  })
})
