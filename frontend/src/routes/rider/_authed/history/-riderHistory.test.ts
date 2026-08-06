import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import { formatHistoryTime, getHistoryErrorMessage, toRiderDeliveryHistory } from './-riderHistory'

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
  it('운행 기록 응답에서 카드가 쓰는 필드를 옮기고 상태는 COMPLETED 로 고정한다', () => {
    expect(
      toRiderDeliveryHistory([
        {
          deliveryId: 12,
          status: 'COMPLETED',
          itemType: 'DOCUMENT',
          pickupRoadAddress: '서울 강남구 테헤란로 152',
          destinationRoadAddress: '서울 송파구 올림픽로 300',
          straightDistanceMeters: 3200,
          completedAt: '2026-08-03T05:30:00Z',
        },
      ]),
    ).toEqual([
      {
        deliveryId: 12,
        completedAt: '2026-08-03T05:30:00Z',
        status: 'COMPLETED',
        itemType: 'DOCUMENT',
        pickupRoadAddress: '서울 강남구 테헤란로 152',
        destinationRoadAddress: '서울 송파구 올림픽로 300',
        straightDistanceMeters: 3200,
      },
    ])
  })

  it('완료 시각을 한국 시간으로 표시하고 잘못된 값은 대체 문구로 바꾼다', () => {
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
