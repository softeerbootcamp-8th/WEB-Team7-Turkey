import { describe, expect, it } from 'vitest'
import { parseFrameType, parseLocationPing } from './useTrackingStream'

describe('parseLocationPing', () => {
  it('유효한 위치 페이로드를 파싱한다', () => {
    const raw = JSON.stringify({
      latitude: 37.4979,
      longitude: 127.0276,
      measuredAt: '2026-08-02T12:34:56.789Z',
      accuracyMeters: 12.5,
    })

    expect(parseLocationPing(raw)).toEqual({
      latitude: 37.4979,
      longitude: 127.0276,
      measuredAt: '2026-08-02T12:34:56.789Z',
      accuracyMeters: 12.5,
    })
  })

  it('accuracyMeters가 없으면 null로 채운다', () => {
    const raw = JSON.stringify({
      latitude: 37.4979,
      longitude: 127.0276,
      measuredAt: '2026-08-02T12:34:56.789Z',
    })

    expect(parseLocationPing(raw)?.accuracyMeters).toBeNull()
  })

  it('JSON이 아니면 null을 반환한다', () => {
    expect(parseLocationPing('not-json')).toBeNull()
  })

  it('필수 필드가 빠지면 null을 반환한다', () => {
    expect(parseLocationPing(JSON.stringify({ latitude: 37.4979 }))).toBeNull()
  })

  it('타입이 안 맞으면 null을 반환한다', () => {
    expect(
      parseLocationPing(JSON.stringify({ latitude: '37.4979', longitude: 127.0276, measuredAt: '2026-08-02T00:00:00Z' })),
    ).toBeNull()
  })
})

describe('parseFrameType', () => {
  it('type: "status" 프레임을 STATUS로 판별한다', () => {
    const raw = JSON.stringify({ type: 'status', status: 'PICKED_UP', occurredAt: '2026-08-02T12:34:56.789Z' })
    expect(parseFrameType(raw)).toBe('STATUS')
  })

  it('type: "location" 프레임을 LOCATION으로 판별한다', () => {
    const raw = JSON.stringify({
      type: 'location',
      latitude: 37.4979,
      longitude: 127.0276,
      measuredAt: '2026-08-02T12:34:56.789Z',
    })
    expect(parseFrameType(raw)).toBe('LOCATION')
  })

  it('type 필드가 없는 레거시 프레임은 LOCATION으로 간주한다', () => {
    const raw = JSON.stringify({
      latitude: 37.4979,
      longitude: 127.0276,
      measuredAt: '2026-08-02T12:34:56.789Z',
    })
    expect(parseFrameType(raw)).toBe('LOCATION')
  })

  it('JSON이 아니면 LOCATION으로 간주한다', () => {
    expect(parseFrameType('not-json')).toBe('LOCATION')
  })
})
