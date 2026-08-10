import { afterEach, describe, expect, it, vi } from 'vitest'

describe('loadKakaoMaps', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.resetModules()
  })

  it('기존 SDK script 실행이 늦어도 maps.load 준비 후 초기화한다', async () => {
    const geocoder = class Geocoder {}
    const maps: Record<string, unknown> = { services: {} }
    let timeoutCount = 0

    vi.stubGlobal('window', {
      kakao: { maps },
      setTimeout: (callback: () => void) => {
        timeoutCount += 1
        maps.load = (onReady: () => void) => {
          maps.services = { Geocoder: geocoder }
          onReady()
        }
        callback()
        return timeoutCount
      },
    })
    vi.stubGlobal('document', {
      querySelector: () => ({ src: 'https://dapi.kakao.com/v2/maps/sdk.js' }),
    })

    const { loadKakaoMaps } = await import('./kakaoMaps')

    await expect(loadKakaoMaps()).resolves.toMatchObject({ maps })
    expect(timeoutCount).toBe(1)
  })
})
