import { describe, expect, it } from 'vitest'
import { formatSeoul, getSeoulYearMonth, parseServerDate } from './datetime'

describe('parseServerDate', () => {
  it('오프셋 없는 서버 문자열을 UTC 로 해석한다(백엔드가 `Z` 를 안 붙이는 실제 형식)', () => {
    // 로컬로 오인하면 시각이 달라진다. UTC 로 봐야 05:30Z 와 같은 순간이다.
    expect(parseServerDate('2026-08-05T05:30:00')?.toISOString()).toBe('2026-08-05T05:30:00.000Z')
  })

  it('이미 `Z`/오프셋이 붙은 문자열은 그대로 존중한다', () => {
    expect(parseServerDate('2026-08-05T05:30:00Z')?.toISOString()).toBe('2026-08-05T05:30:00.000Z')
    expect(parseServerDate('2026-08-05T14:30:00+09:00')?.toISOString()).toBe('2026-08-05T05:30:00.000Z')
  })

  it('밀리초가 있는 오프셋 없는 문자열도 UTC 로 해석한다', () => {
    expect(parseServerDate('2026-08-05T05:30:00.123')?.toISOString()).toBe('2026-08-05T05:30:00.123Z')
  })

  it('값이 없거나 파싱 불가면 null', () => {
    expect(parseServerDate(undefined)).toBeNull()
    expect(parseServerDate(null)).toBeNull()
    expect(parseServerDate('')).toBeNull()
    expect(parseServerDate('invalid')).toBeNull()
  })
})

describe('formatSeoul', () => {
  it('표시 타임존을 항상 Asia/Seoul 로 고정한다 — Z 유무와 무관하게 같은 KST 결과', () => {
    const options = { hour: '2-digit', minute: '2-digit', hour12: false } as const
    // UTC 05:30 → KST 14:30
    expect(formatSeoul('2026-08-05T05:30:00', options)).toBe('14:30')
    expect(formatSeoul('2026-08-05T05:30:00Z', options)).toBe('14:30')
  })

  it('options 로 timeZone 을 넘겨도 서울이 이긴다', () => {
    expect(
      formatSeoul('2026-08-05T05:30:00Z', {
        timeZone: 'America/New_York',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }),
    ).toBe('14:30')
  })

  it('파싱 불가면 null 을 반환해 호출부가 대체 문구를 정하게 한다', () => {
    expect(formatSeoul(undefined, { hour: '2-digit' })).toBeNull()
    expect(formatSeoul('invalid', { hour: '2-digit' })).toBeNull()
  })
})

describe('getSeoulYearMonth', () => {
  it('연·월을 한국 시간 기준(0-based month)으로 뽑는다', () => {
    expect(getSeoulYearMonth('2026-08-03T05:30:00Z')).toEqual({ year: 2026, month: 7 })
  })

  it('월 경계: UTC 로는 7월 말이지만 KST 로는 8월인 순간을 8월로 분류한다', () => {
    // UTC 2026-07-31T16:00 → KST 2026-08-01T01:00
    expect(getSeoulYearMonth('2026-07-31T16:00:00Z')).toEqual({ year: 2026, month: 7 })
    // 오프셋 없는 실제 백엔드 형식도 동일하게 UTC 로 본다
    expect(getSeoulYearMonth('2026-07-31T16:00:00')).toEqual({ year: 2026, month: 7 })
  })

  it('파싱 불가면 null', () => {
    expect(getSeoulYearMonth(undefined)).toBeNull()
    expect(getSeoulYearMonth('invalid')).toBeNull()
  })
})
