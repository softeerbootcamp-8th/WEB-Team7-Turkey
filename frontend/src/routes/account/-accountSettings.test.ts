import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import type { ApiResponseVoid } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { resolveLogoutFailure } from './-accountSettings'

function axiosErrorWithStatus(status: number, body?: ApiResponseVoid): AxiosError<ApiResponseVoid> {
  const config = { headers: new AxiosHeaders() }
  const response = {
    status,
    statusText: '',
    data: body ?? {},
    headers: {},
    config,
  } as AxiosResponse<ApiResponseVoid>

  return new AxiosError('request failed', String(status), config, {}, response)
}

function networkError(): AxiosError<ApiResponseVoid> {
  return new AxiosError('Network Error', AxiosError.ERR_NETWORK, { headers: new AxiosHeaders() }, {})
}

describe('resolveLogoutFailure', () => {
  it('409 는 서버가 상태를 근거로 거부한 것이므로 화면에 머무는 blocked 로 분류한다', () => {
    const failure = resolveLogoutFailure(
      axiosErrorWithStatus(409, { success: false, message: '배송 진행 중에는 로그아웃할 수 없습니다.' }),
    )

    expect(failure).toEqual({ kind: 'blocked', message: '배송 진행 중에는 로그아웃할 수 없습니다.' })
  })

  it('409 에 서버 메시지가 없어도 blocked 로 분류하고 기본 문구를 쓴다', () => {
    const failure = resolveLogoutFailure(axiosErrorWithStatus(409))

    expect(failure.kind).toBe('blocked')
    expect(failure).toHaveProperty('message', '지금은 로그아웃할 수 없습니다.')
  })

  it('401 은 세션이 이미 없다는 뜻이라 오류가 아니라 already-signed-out 이다', () => {
    expect(resolveLogoutFailure(axiosErrorWithStatus(401))).toEqual({ kind: 'already-signed-out' })
  })

  it('응답이 없는 네트워크 오류는 성사 여부를 알 수 없어 local-only 다', () => {
    const failure = resolveLogoutFailure(networkError())

    expect(failure.kind).toBe('local-only')
    expect(failure).toHaveProperty(
      'message',
      '서버에 연결하지 못해 로그아웃을 확인하지 못했습니다. 이 기기에서는 로그아웃 처리했습니다.',
    )
  })

  it('5xx 는 local-only 로 분류하고 서버 메시지가 있으면 그대로 보여준다', () => {
    const failure = resolveLogoutFailure(axiosErrorWithStatus(500, { success: false, message: '일시적인 오류입니다.' }))

    expect(failure).toEqual({ kind: 'local-only', message: '일시적인 오류입니다.' })
  })

  it('axios 오류가 아닌 예외도 local-only 로 떨어뜨려 캐시 정리를 놓치지 않는다', () => {
    const failure = resolveLogoutFailure(new Error('boom'))

    expect(failure.kind).toBe('local-only')
  })
})
