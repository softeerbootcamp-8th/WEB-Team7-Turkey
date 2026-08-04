import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import { formatHistoryTime, getHistoryErrorMessage, isSameHistoryDay, toRiderDeliveryHistory } from './-riderHistory'

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

describe('배송 기록 표시값', () => {
  it('정산 응답에서 포인트 값을 제외한 배송 기록만 만든다', () => {
    expect(
      toRiderDeliveryHistory([
        {
          deliveryId: 12,
          settledAt: '2026-08-03T05:30:00Z',
          settlementAmount: 9000,
        },
      ]),
    ).toEqual([
      {
        deliveryId: 12,
        completedAt: '2026-08-03T05:30:00Z',
        status: 'COMPLETED',
      },
    ])
  })

  it('한국 날짜를 기준으로 선택한 날짜의 기록을 구분한다', () => {
    expect(isSameHistoryDay('2026-08-03T15:30:00Z', new Date('2026-08-04T01:00:00+09:00'))).toBe(true)
    expect(formatHistoryTime('2026-08-03T05:30:00Z')).not.toBe('완료 시각 미제공')
    expect(formatHistoryTime('invalid')).toBe('완료 시각 미제공')
  })
})

describe('배송 기록 오류 문구', () => {
  it('서버 오류 메시지와 네트워크 오류를 구분한다', () => {
    expect(getHistoryErrorMessage(httpError('배송 기록 조회 실패'))).toBe('배송 기록 조회 실패')
    expect(getHistoryErrorMessage(new AxiosError('Network Error', AxiosError.ERR_NETWORK))).toBe(
      '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
    )
  })
})
