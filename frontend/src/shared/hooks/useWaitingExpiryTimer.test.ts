import { describe, expect, it } from 'vitest'
import { decideExpiry, WAITING_TIMEOUT_MS } from './useWaitingExpiryTimer'

describe('decideExpiry', () => {
  const requestedAt = '2026-08-10T00:00:00.000Z'
  const requestedAtMs = new Date(requestedAt).getTime()
  const expiresAtMs = requestedAtMs + WAITING_TIMEOUT_MS

  it('만료 시각 이전이면 pending과 남은 시간을 반환한다', () => {
    const oneMinuteBefore = expiresAtMs - 60_000

    expect(decideExpiry(oneMinuteBefore, requestedAt)).toEqual({ kind: 'pending', remainingMs: 60_000 })
  })

  it('정확히 만료 시각이면 expired다(경계값)', () => {
    expect(decideExpiry(expiresAtMs, requestedAt)).toEqual({ kind: 'expired' })
  })

  it('만료 시각을 지났으면 expired다', () => {
    const tenMinutesAfter = expiresAtMs + 10 * 60_000

    expect(decideExpiry(tenMinutesAfter, requestedAt)).toEqual({ kind: 'expired' })
  })

  it('생성 직후(0분 경과)면 5분 전체가 남아있다', () => {
    expect(decideExpiry(requestedAtMs, requestedAt)).toEqual({ kind: 'pending', remainingMs: WAITING_TIMEOUT_MS })
  })

  it('실제 백엔드 형식(Z 없음)도 UTC로 해석한다 — 로컬 타임존으로 새면 방금 생성된 주문도 즉시 만료로 오판한다(실측으로 확인한 회귀)', () => {
    const noZRequestedAt = '2026-08-10T00:00:00.000'
    const sameInstantMs = new Date('2026-08-10T00:00:00.000Z').getTime()

    // 생성 직후(0분 경과)라면, 타임존과 무관하게 5분이 그대로 남아 있어야 한다.
    expect(decideExpiry(sameInstantMs, noZRequestedAt)).toEqual({ kind: 'pending', remainingMs: WAITING_TIMEOUT_MS })
  })

  it('파싱할 수 없는 값은 만료로 오판하지 않고 재시도하도록 pending을 반환한다', () => {
    expect(decideExpiry(Date.now(), 'not-a-date')).toEqual({ kind: 'pending', remainingMs: WAITING_TIMEOUT_MS })
  })
})
