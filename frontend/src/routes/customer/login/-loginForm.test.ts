import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import { getCustomerLoginErrorMessage, validateCustomerLogin } from './-loginForm'

function httpError(status: number): AxiosError {
  const response = { status, data: null, statusText: '', headers: {}, config: {} } as unknown as AxiosResponse
  return new AxiosError('http error', String(status), { headers: new AxiosHeaders() }, undefined, response)
}

describe('validateCustomerLogin', () => {
  it('아이디와 비밀번호가 모두 있으면 오류가 없다', () => {
    expect(validateCustomerLogin({ loginId: 'customer1', password: 'password' })).toEqual({})
  })

  it('공백 아이디와 빈 비밀번호를 각각 검증한다', () => {
    expect(validateCustomerLogin({ loginId: '   ', password: '' })).toEqual({
      loginId: '아이디를 입력해 주세요.',
      password: '비밀번호를 입력해 주세요.',
    })
  })
})

describe('getCustomerLoginErrorMessage', () => {
  it('401은 계정 존재 여부를 노출하지 않는 공통 문구를 반환한다', () => {
    expect(getCustomerLoginErrorMessage(httpError(401))).toBe('아이디 또는 비밀번호가 일치하지 않습니다.')
  })

  it('응답이 없는 네트워크 오류는 재시도 가능한 안내를 반환한다', () => {
    expect(getCustomerLoginErrorMessage(new AxiosError('Network Error', AxiosError.ERR_NETWORK))).toBe(
      '네트워크 연결을 확인하고 다시 시도해 주세요.',
    )
  })

  it('그 밖의 서버 오류는 일반 오류 문구를 반환한다', () => {
    expect(getCustomerLoginErrorMessage(httpError(500))).toBe(
      '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
    )
  })
})

