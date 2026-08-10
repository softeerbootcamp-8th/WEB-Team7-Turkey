import { describe, expect, it } from 'vitest'
import { nextTrackingSignal, parseFrameStatus, parseFrameType, parseLocationPing } from './useTrackingStream'

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

describe('parseFrameStatus', () => {
  it('위치 프레임에 실린 배송 상태를 읽는다', () => {
    const raw = JSON.stringify({
      type: 'location',
      status: 'DELIVERING',
      latitude: 37.4979,
      longitude: 127.0276,
      measuredAt: '2026-08-02T12:34:56.789Z',
    })
    expect(parseFrameStatus(raw)).toBe('DELIVERING')
  })

  it('status가 없으면 null을 돌려준다 — 구버전 백엔드에서 기존 동작을 유지하기 위함', () => {
    const raw = JSON.stringify({
      type: 'location',
      latitude: 37.4979,
      longitude: 127.0276,
      measuredAt: '2026-08-02T12:34:56.789Z',
    })
    expect(parseFrameStatus(raw)).toBeNull()
  })

  it('status가 null이면 null을 돌려준다 — Redis에서 복원한 위치에는 상태가 없다', () => {
    const raw = JSON.stringify({ type: 'location', status: null, latitude: 37.4979, longitude: 127.0276 })
    expect(parseFrameStatus(raw)).toBeNull()
  })

  it('status가 문자열이 아니면 null을 돌려준다', () => {
    expect(parseFrameStatus(JSON.stringify({ status: 42 }))).toBeNull()
  })

  it('JSON이 아니면 null을 돌려준다 — 프레임 하나가 스트림을 멈추면 안 된다', () => {
    expect(parseFrameStatus('not-json')).toBeNull()
  })

  it('상태 전이 프레임의 status도 같은 방식으로 읽힌다', () => {
    // STATUS 프레임은 parseFrameType이 먼저 걸러내므로 훅에서는 이 경로로 오지 않지만,
    // 두 프레임이 같은 필드명을 쓴다는 사실 자체를 고정해 둔다.
    const raw = JSON.stringify({ type: 'status', status: 'COMPLETED', occurredAt: '2026-08-02T00:00:00Z' })
    expect(parseFrameStatus(raw)).toBe('COMPLETED')
  })
})

describe('nextTrackingSignal', () => {
  const location = (status: string | null) => ({ type: 'LOCATION' as const, status })
  const statusFrame = (status: string | null) => ({ type: 'STATUS' as const, status })

  it('STATUS 프레임은 항상 재조회하고 기준값을 올린다', () => {
    expect(nextTrackingSignal(statusFrame('PICKED_UP'), 'MOVING_TO_PICKUP')).toEqual({
      lastStatus: 'PICKED_UP',
      refetch: true,
    })
  })

  it('STATUS 직후 같은 상태의 위치 프레임은 재조회하지 않는다 — 한 전이에 한 번만', () => {
    // STATUS(빠른 경로)와 위치(안전망)가 같은 전이를 가리킬 때 두 번 반응하면
    // 전이마다 REST가 두 번 나간다.
    const afterStatus = nextTrackingSignal(statusFrame('PICKED_UP'), 'MOVING_TO_PICKUP')
    expect(nextTrackingSignal(location('PICKED_UP'), afterStatus.lastStatus)).toEqual({
      lastStatus: 'PICKED_UP',
      refetch: false,
    })
  })

  it('위치 프레임의 상태가 달라지면 재조회한다 — 그 사이 STATUS를 놓친 것', () => {
    expect(nextTrackingSignal(location('DELIVERING'), 'PICKED_UP')).toEqual({
      lastStatus: 'DELIVERING',
      refetch: true,
    })
  })

  it('같은 상태가 반복되면 재조회하지 않는다 — 5초마다 REST를 때리지 않기 위함', () => {
    expect(nextTrackingSignal(location('DELIVERING'), 'DELIVERING')).toEqual({
      lastStatus: 'DELIVERING',
      refetch: false,
    })
  })

  it('첫 위치 프레임은 기준값만 세우고 재조회하지 않는다 — 진입 시 REST로 이미 읽었다', () => {
    expect(nextTrackingSignal(location('ASSIGNED'), null)).toEqual({
      lastStatus: 'ASSIGNED',
      refetch: false,
    })
  })

  it('상태가 없는 위치 프레임은 기준값을 유지하고 아무것도 하지 않는다 — 구버전 백엔드', () => {
    expect(nextTrackingSignal(location(null), 'DELIVERING')).toEqual({
      lastStatus: 'DELIVERING',
      refetch: false,
    })
  })
})
