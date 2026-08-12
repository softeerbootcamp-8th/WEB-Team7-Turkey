import { describe, expect, it } from 'vitest'
import { formatEtaCountdown, getEtaLabel } from './-eta'

describe('getEtaLabel', () => {
  it('픽업 전에는 픽업지 기준임을 알린다', () => {
    expect(getEtaLabel('ASSIGNED')).toBe('픽업까지')
    expect(getEtaLabel('MOVING_TO_PICKUP')).toBe('픽업까지')
  })

  it('픽업 후에는 도착지 기준임을 알린다', () => {
    expect(getEtaLabel('PICKED_UP')).toBe('도착까지')
    expect(getEtaLabel('DELIVERING')).toBe('도착까지')
  })
})

describe('formatEtaCountdown', () => {
  // 서버는 오프셋 없는 UTC 문자열을 준다(parseServerDate 가 Z 를 붙여 해석한다).
  const arrival = '2026-08-10T05:00:00'

  it('남은 시간을 분 단위로 내림해 보여준다', () => {
    // 7분 30초 남았으면 "약 7분" — 올림하면 실제보다 늦게 도착한다고 안내하게 된다.
    expect(formatEtaCountdown(arrival, new Date('2026-08-10T04:52:30Z'))).toBe('약 7분')
    expect(formatEtaCountdown(arrival, new Date('2026-08-10T04:00:00Z'))).toBe('약 60분')
  })

  it('1분 미만은 곧 도착으로 뭉갠다', () => {
    expect(formatEtaCountdown(arrival, new Date('2026-08-10T04:59:30Z'))).toBe('곧 도착')
    expect(formatEtaCountdown(arrival, new Date('2026-08-10T05:00:00Z'))).toBe('곧 도착')
  })

  it('예정 시각이 지나도 지연을 세지 않고 곧 도착에 멈춘다', () => {
    // OSRM 이 자유흐름 기준이라 원래 낙관적이다 — 음수를 노출하면 정상 배송도 상시 지연으로 보인다.
    expect(formatEtaCountdown(arrival, new Date('2026-08-10T05:20:00Z'))).toBe('곧 도착')
  })

  it('값이 없거나 파싱 불가면 null 이다', () => {
    // 서버가 산정 불가를 null 로 주는 경우(배차 전·완료·취소, 라이더 위치 없음, 경로 서버 장애).
    expect(formatEtaCountdown(null)).toBeNull()
    expect(formatEtaCountdown(undefined)).toBeNull()
    expect(formatEtaCountdown('not-a-date')).toBeNull()
  })
})
