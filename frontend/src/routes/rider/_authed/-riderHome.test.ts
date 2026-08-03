import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import { getGoOnlineErrorMessage } from './-riderHome'

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
    expect(getGoOnlineErrorMessage(httpError(409, '배송 중에는 상태를 변경할 수 없습니다.'))).toBe(
      '배송 중에는 상태를 변경할 수 없습니다.',
    )
  })

  it('네트워크 오류를 구분한다', () => {
    expect(getGoOnlineErrorMessage(new AxiosError('Network Error', AxiosError.ERR_NETWORK))).toBe(
      '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
    )
  })

  it('알 수 없는 오류에는 기본 문구를 표시한다', () => {
    expect(getGoOnlineErrorMessage(new Error('unknown'))).toBe(
      '운행을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  })
})
