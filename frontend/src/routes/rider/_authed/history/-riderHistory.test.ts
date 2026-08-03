import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import { formatSettlementAmount, formatSettlementDate, getSettlementErrorMessage } from './-riderHistory'

function httpError(message?: string): AxiosError<{ message?: string }> {
  const response: AxiosResponse<{ message?: string }> = {
    data: { message },
    status: 500,
    statusText: 'Error',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
  }
  return new AxiosError('http error', '500', response.config, undefined, response)
}

describe('배송 이력 표시값', () => {
  it('정산 금액을 원 단위로 표시한다', () => {
    expect(formatSettlementAmount(13600)).toBe('13,600원')
    expect(formatSettlementAmount()).toBe('0원')
  })

  it('UTC 시각을 한국 시각으로 표시한다', () => {
    expect(formatSettlementDate('2026-08-03T05:30:00Z')).toContain('2026. 08. 03.')
    expect(formatSettlementDate()).toBe('정산 시각 미제공')
    expect(formatSettlementDate('invalid')).toBe('정산 시각 미제공')
  })
})

describe('배송 이력 오류 문구', () => {
  it('서버 오류 메시지를 표시한다', () => {
    expect(getSettlementErrorMessage(httpError('정산 내역 조회 실패'))).toBe('정산 내역 조회 실패')
  })

  it('네트워크 오류를 구분한다', () => {
    expect(getSettlementErrorMessage(new AxiosError('Network Error', AxiosError.ERR_NETWORK))).toBe(
      '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
    )
  })
})
