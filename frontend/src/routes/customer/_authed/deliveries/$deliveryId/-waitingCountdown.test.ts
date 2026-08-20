import { describe, expect, it } from 'vitest'
import { formatWaitingCountdown } from './-waitingCountdown'

// 서버 시각은 오프셋 없는 UTC 로 온다(parseServerDate 가 Z 를 붙인다). 픽스처도 그 형태로 둔다.
const REQUESTED_AT = '2026-08-14T00:00:00'
const REQUESTED_AT_MS = Date.parse(`${REQUESTED_AT}Z`)

describe('formatWaitingCountdown', () => {
  it('주문 직후에는 100분 전체를 100:00 으로 보여 준다', () => {
    expect(formatWaitingCountdown(REQUESTED_AT, REQUESTED_AT_MS)).toBe('100:00')
  })

  it('경과 시간만큼 줄어든 남은 시간을 M:SS 로 보여 준다', () => {
    // 2분 33초 경과 → 남은 97분 27초
    const now = REQUESTED_AT_MS + (2 * 60 + 33) * 1000
    expect(formatWaitingCountdown(REQUESTED_AT, now)).toBe('97:27')
  })

  it('초는 항상 두 자리로 zero-pad 한다', () => {
    // 99분 53초 경과 → 남은 7초
    const now = REQUESTED_AT_MS + (99 * 60 + 53) * 1000
    expect(formatWaitingCountdown(REQUESTED_AT, now)).toBe('0:07')
  })

  it('남은 시간이 1초 미만이면 올림해 0:01 을 보여 준다', () => {
    // 만료 400ms 전
    const now = REQUESTED_AT_MS + 100 * 60 * 1000 - 400
    expect(formatWaitingCountdown(REQUESTED_AT, now)).toBe('0:01')
  })

  it('만료 시점 이후에는 null 을 반환한다', () => {
    const now = REQUESTED_AT_MS + 100 * 60 * 1000
    expect(formatWaitingCountdown(REQUESTED_AT, now)).toBeNull()
    expect(formatWaitingCountdown(REQUESTED_AT, now + 1000)).toBeNull()
  })

  it('requestedAt 이 없으면 null 을 반환한다', () => {
    expect(formatWaitingCountdown(undefined, REQUESTED_AT_MS)).toBeNull()
    expect(formatWaitingCountdown(null, REQUESTED_AT_MS)).toBeNull()
  })

  it('파싱 불가한 값은 만료로 오판하지 않고 전체 시간을 보여 준다', () => {
    // decideExpiry 가 파싱 실패를 pending(전체) 으로 처리한다 — 잘못된 조기 취소 방지와 같은 원칙.
    expect(formatWaitingCountdown('not-a-date', REQUESTED_AT_MS)).toBe('100:00')
  })
})
