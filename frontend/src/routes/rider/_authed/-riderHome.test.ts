import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import { getOperatingStatusErrorMessage, getOperatingStatusQueryErrorMessage } from './-riderHome'

function httpError(status: number, message?: string): AxiosError<{ message?: string }> {
  const response: AxiosResponse<{ message?: string }> = {
    data: { message },
    status,
    statusText: 'Error',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
  }
  return new AxiosError('http error', String(status), response.config, undefined, response)
}

describe('퀵 시작 오류 문구', () => {
  it('서버가 제공한 오류 메시지를 표시한다', () => {
    expect(getOperatingStatusErrorMessage(httpError(409, '배송 중에는 상태를 변경할 수 없습니다.'), 'GO_OFFLINE')).toBe(
      '배송 중에는 상태를 변경할 수 없습니다.',
    )
  })

  it('네트워크 오류를 구분한다', () => {
    expect(getOperatingStatusErrorMessage(new AxiosError('Network Error', AxiosError.ERR_NETWORK), 'GO_ONLINE')).toBe(
      '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
    )
  })

  it('알 수 없는 오류에는 기본 문구를 표시한다', () => {
    expect(getOperatingStatusErrorMessage(new Error('unknown'), 'GO_ONLINE')).toBe(
      '운행을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  })

  it('종료 실패에는 종료 기본 문구를 표시한다', () => {
    expect(getOperatingStatusErrorMessage(new Error('unknown'), 'GO_OFFLINE')).toBe(
      '운행을 종료하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  })
})

describe('운행 상태 조회 오류 문구', () => {
  it('서버가 제공한 오류 메시지를 표시한다', () => {
    expect(getOperatingStatusQueryErrorMessage(httpError(500, '상태 조회에 실패했습니다.'))).toBe('상태 조회에 실패했습니다.')
  })

  it('네트워크 오류를 구분한다', () => {
    expect(getOperatingStatusQueryErrorMessage(new AxiosError('Network Error', AxiosError.ERR_NETWORK))).toBe(
      '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
    )
  })
})
