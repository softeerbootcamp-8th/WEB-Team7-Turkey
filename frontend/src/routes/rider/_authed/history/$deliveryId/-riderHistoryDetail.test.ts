import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import {
  formatDetailDate,
  formatDetailTime,
  formatDistance,
  formatItemType,
  formatMoney,
  formatProofType,
  getDetailErrorMessage,
  resolveCompletedAt,
  toTimelineSteps,
} from './-riderHistoryDetail'

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

function networkError(): AxiosError {
  return new AxiosError('network error', 'ERR_NETWORK', { headers: new AxiosHeaders() })
}

describe('운행 상세 표시값', () => {
  it('물품/거리/금액을 사람이 읽는 형태로 옮기고 값이 없으면 대체 문구를 준다', () => {
    expect(formatItemType('DOCUMENT')).toBe('문서')
    expect(formatItemType(undefined)).toBe('물품 정보 없음')
    expect(formatDistance(800)).toBe('800m')
    expect(formatDistance(3200)).toBe('3.2km')
    expect(formatDistance(undefined)).toBe('거리 정보 없음')
    expect(formatMoney(6400)).toBe('6,400원')
    expect(formatMoney(undefined)).toBe('금액 미정')
    expect(formatProofType('RECIPIENT_CONFIRMATION')).toBe('수령인 확인')
    expect(formatProofType(undefined)).toBe('인증 정보 없음')
  })

  it('날짜·시각을 한국 시간으로 표시하고 잘못된 값은 대체 문구로 바꾼다', () => {
    expect(formatDetailDate('2026-08-05T05:30:00Z')).toBe('2026.08.05')
    expect(formatDetailDate('invalid')).toBe('날짜 미제공')
    expect(formatDetailTime('2026-08-05T05:30:00Z')).toBe('14:30')
    expect(formatDetailTime(undefined)).toBe('시각 미제공')
  })
})

describe('상태 이력 타임라인', () => {
  it('도달한 단계만 라벨·시각으로 옮기고 시각 없는 항목은 버린다', () => {
    const steps = toTimelineSteps([
      { status: 'WAITING', occurredAt: '2026-08-05T04:00:00Z' },
      { status: 'ASSIGNED', occurredAt: '2026-08-05T04:05:00Z' },
      { status: 'COMPLETED', occurredAt: undefined }, // 방어: 버려져야 한다
    ])
    expect(steps).toEqual([
      { status: 'WAITING', label: '접수 완료', time: '13:00' },
      { status: 'ASSIGNED', label: '기사 배정', time: '13:05' },
    ])
  })

  it('steps 가 없으면 빈 배열이다', () => {
    expect(toTimelineSteps(undefined)).toEqual([])
  })

  it('완료 시각은 COMPLETED 단계에서 얻고, 없으면 정산 시각으로 보완한다', () => {
    expect(
      resolveCompletedAt(
        [{ status: 'COMPLETED', occurredAt: '2026-08-05T05:30:00Z' }],
        '2026-08-05T06:00:00Z',
      ),
    ).toBe('2026-08-05T05:30:00Z')
    expect(resolveCompletedAt([], '2026-08-05T06:00:00Z')).toBe('2026-08-05T06:00:00Z')
    expect(resolveCompletedAt(undefined, undefined)).toBeUndefined()
  })
})

describe('운행 상세 오류 문구', () => {
  it('404 는 목록 안내 문구로 바꾼다(존재하지 않거나 타인 것)', () => {
    expect(getDetailErrorMessage(httpError(404))).toBe(
      '존재하지 않거나 접근할 수 없는 운행 기록입니다. 목록에서 다시 확인해 주세요.',
    )
  })

  it('네트워크 오류와 서버 메시지를 구분하고, axios 가 아니면 기본 문구를 준다', () => {
    expect(getDetailErrorMessage(networkError())).toBe('서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.')
    expect(getDetailErrorMessage(httpError(500, '서버 폭발'))).toBe('서버 폭발')
    expect(getDetailErrorMessage(new Error('unknown'))).toBe(
      '운행 상세 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  })
})
