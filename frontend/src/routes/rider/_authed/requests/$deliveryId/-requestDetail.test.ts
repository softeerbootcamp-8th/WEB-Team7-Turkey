import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import {
  classifyAcceptFailure,
  formatDistance,
  formatItemType,
  formatMinutes,
  formatMoney,
  formatRequestedAt,
  getAcceptErrorMessage,
  getDetailErrorMessage,
} from './-requestDetail'

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

describe('콜 상세 표시값', () => {
  it('물품 종류를 한글로 표시한다', () => {
    expect(formatItemType('MEDIUM_PARCEL')).toBe('중형')
    expect(formatItemType(undefined)).toBe('물품 정보 없음')
  })

  it('거리와 운임 및 예상 시간을 표시한다', () => {
    expect(formatDistance(850)).toBe('850m')
    expect(formatDistance(4100)).toBe('4.1km')
    expect(formatMoney(6730)).toBe('6,730P')
    expect(formatMinutes(18)).toBe('약 18분')
  })

  it('유효한 요청 시각만 표시한다', () => {
    expect(formatRequestedAt(undefined)).toBeNull()
    expect(formatRequestedAt('invalid')).toBeNull()
    expect(formatRequestedAt('2026-08-03T04:00:00Z')).not.toBeNull()
  })
})

describe('상세 조회 오류', () => {
  it('404를 이미 처리된 콜로 안내한다', () => {
    expect(getDetailErrorMessage(httpError(404))).toBe('이미 배차되었거나 취소된 콜입니다.')
  })

  it('네트워크 오류를 구분한다', () => {
    expect(getDetailErrorMessage(new AxiosError('Network Error', AxiosError.ERR_NETWORK))).toBe(
      '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
    )
  })
})

describe('콜 수락 오류', () => {
  it('사유와 무관하게 409는 declined로 분류한다(#520)', () => {
    expect(classifyAcceptFailure(httpError(409))).toBe('declined')
    expect(classifyAcceptFailure(httpError(409, '이미 다른 라이더가 배차를 확정했습니다.'))).toBe('declined')
    expect(classifyAcceptFailure(httpError(409, '배송요청이 취소되어 배차를 확정할 수 없습니다.'))).toBe('declined')
  })

  it('409 사유는 서버 message를 그대로 화면에 쓴다(#520)', () => {
    expect(getAcceptErrorMessage(httpError(409, '배송요청이 취소되어 배차를 확정할 수 없습니다.'))).toBe(
      '배송요청이 취소되어 배차를 확정할 수 없습니다.',
    )
  })

  it('네트워크 오류와 5xx를 결과 불명으로 분류한다', () => {
    expect(classifyAcceptFailure(new AxiosError('Network Error', AxiosError.ERR_NETWORK))).toBe('uncertain')
    expect(classifyAcceptFailure(httpError(500))).toBe('uncertain')
  })

  it('그 밖의 4xx는 명확한 실패로 분류한다', () => {
    expect(classifyAcceptFailure(httpError(403))).toBe('failure')
  })

  it('서버 오류 메시지를 표시한다', () => {
    expect(getAcceptErrorMessage(httpError(403, '라이더 운행 상태가 AVAILABLE이 아닙니다.'))).toBe(
      '라이더 운행 상태가 AVAILABLE이 아닙니다.',
    )
  })
})
