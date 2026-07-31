import { QueryClient } from '@tanstack/react-query'
import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 세션 합성 조회의 분기를 고정한다.
 *
 * 여기서 틀리면 증상이 큰 쪽으로 난다 — 401 이 아닌 실패를 만료로 처리하면 서버가 잠깐 흔들릴 때
 * 접속자 전원이 로그인 화면으로 튕긴다(#195 예외 흐름).
 */

const getCustomerSession = vi.fn()
const getRiderSession = vi.fn()

vi.mock('@/api/generated/customer-session/customer-session', () => ({
  getCustomerSession: (...args: unknown[]) => getCustomerSession(...args),
  getGetCustomerSessionQueryKey: () => ['/api/customer/session'],
}))

vi.mock('@/api/generated/rider-session/rider-session', () => ({
  getRiderSession: (...args: unknown[]) => getRiderSession(...args),
  getGetRiderSessionQueryKey: () => ['/api/rider/session'],
}))

const { ensureSessionInfo, clearSessionQueries, isUnauthorized } = await import('./session')

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
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
})

describe('ensureSessionInfo - 역할 판정', () => {
  it('고객 세션이 200 이면 CUSTOMER 로 판정한다', async () => {
    getCustomerSession.mockResolvedValue({ success: true, data: { memberId: 1, loginId: 'c1', name: '고객' } })
    getRiderSession.mockRejectedValue(httpError(401))

    await expect(ensureSessionInfo(queryClient)).resolves.toEqual({
      role: 'CUSTOMER',
      customer: { memberId: 1, loginId: 'c1', name: '고객' },
    })
  })

  it('라이더 세션이 200 이면 RIDER 로 판정하고 운행 상태를 함께 담는다', async () => {
    getCustomerSession.mockRejectedValue(httpError(401))
    getRiderSession.mockResolvedValue({
      success: true,
      data: { memberId: 2, loginId: 'r1', name: '라이더', operatingStatus: 'BUSY' },
    })

    await expect(ensureSessionInfo(queryClient)).resolves.toEqual({
      role: 'RIDER',
      rider: { memberId: 2, loginId: 'r1', name: '라이더', operatingStatus: 'BUSY' },
    })
  })

  it('양쪽 모두 401 이면 비로그인으로 판정한다', async () => {
    getCustomerSession.mockRejectedValue(httpError(401))
    getRiderSession.mockRejectedValue(httpError(401))

    await expect(ensureSessionInfo(queryClient)).resolves.toEqual({ role: null })
  })
})

describe('ensureSessionInfo - 실패를 만료로 오해하지 않는다', () => {
  it('한쪽이 5xx 면 비로그인이 아니라 오류를 올린다', async () => {
    getCustomerSession.mockRejectedValue(httpError(500))
    getRiderSession.mockRejectedValue(httpError(401))

    await expect(ensureSessionInfo(queryClient)).rejects.toMatchObject({ response: { status: 500 } })
  })

  it('네트워크 오류도 비로그인이 아니라 오류를 올린다', async () => {
    getCustomerSession.mockRejectedValue(networkError())
    getRiderSession.mockRejectedValue(networkError())

    await expect(ensureSessionInfo(queryClient)).rejects.toMatchObject({ code: AxiosError.ERR_NETWORK })
  })

  it('200 인데 body 에 data 가 없으면 오류를 올린다', async () => {
    getCustomerSession.mockResolvedValue({ success: true })
    getRiderSession.mockRejectedValue(httpError(401))

    await expect(ensureSessionInfo(queryClient)).rejects.toThrow(/data 가 없습니다/)
  })
})

describe('ensureSessionInfo - 캐시 공유', () => {
  it('신선한 세션이 캐시에 있으면 어느 세션 API 도 다시 부르지 않는다', async () => {
    getCustomerSession.mockResolvedValue({ success: true, data: { memberId: 1, loginId: 'c1', name: '고객' } })
    getRiderSession.mockRejectedValue(httpError(401))

    await ensureSessionInfo(queryClient)
    expect(getCustomerSession).toHaveBeenCalledTimes(1)
    expect(getRiderSession).toHaveBeenCalledTimes(1)

    // 두 번째 가드 평가(=화면 전환). 실패가 예정된 라이더 세션 요청을 반복하지 않아야 한다.
    await ensureSessionInfo(queryClient)
    expect(getCustomerSession).toHaveBeenCalledTimes(1)
    expect(getRiderSession).toHaveBeenCalledTimes(1)
  })

  it('세션 캐시를 지우면 다시 조회한다', async () => {
    getCustomerSession.mockResolvedValue({ success: true, data: { memberId: 1, loginId: 'c1', name: '고객' } })
    getRiderSession.mockRejectedValue(httpError(401))

    await ensureSessionInfo(queryClient)
    clearSessionQueries(queryClient)
    await ensureSessionInfo(queryClient)

    expect(getCustomerSession).toHaveBeenCalledTimes(2)
  })
})

describe('isUnauthorized', () => {
  it('401 만 참으로 본다', () => {
    expect(isUnauthorized(httpError(401))).toBe(true)
    expect(isUnauthorized(httpError(403))).toBe(false)
    expect(isUnauthorized(httpError(500))).toBe(false)
    expect(isUnauthorized(networkError())).toBe(false)
    expect(isUnauthorized(new Error('boom'))).toBe(false)
  })
})
