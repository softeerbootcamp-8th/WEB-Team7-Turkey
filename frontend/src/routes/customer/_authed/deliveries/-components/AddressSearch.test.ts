import { beforeEach, describe, expect, it, vi } from 'vitest'
import { loadKakaoMaps } from '@/lib/kakaoMaps'
import { geocodeAddress, getGeocodeCandidates } from './AddressSearch'

vi.mock('@/lib/kakaoMaps', () => ({
  loadKakaoMaps: vi.fn(),
}))

const mockedLoadKakaoMaps = vi.mocked(loadKakaoMaps)

function mockGeocoder(responses: Record<string, { status: string; x?: string; y?: string }>) {
  const addressSearch = vi.fn((address: string, callback: (
    results: Array<{ x: string; y: string }>,
    status: string,
  ) => void) => {
    const response = responses[address]
    callback(
      response?.x && response.y ? [{ x: response.x, y: response.y }] : [],
      response?.status ?? 'ZERO_RESULT',
    )
  })
  class Geocoder {
    addressSearch = addressSearch
  }

  mockedLoadKakaoMaps.mockResolvedValue({
    maps: {
      services: {
        Status: { OK: 'OK', ZERO_RESULT: 'ZERO_RESULT', ERROR: 'ERROR' },
        Geocoder,
      },
    },
  } as unknown as typeof kakao)

  return addressSearch
}

describe('주소 좌표 변환', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('우편번호 결과에서 중복과 빈 값을 제외한 fallback 후보를 만든다', () => {
    expect(getGeocodeCandidates({
      roadAddress: '서울 용산구 새창로 78',
      jibunAddress: '서울 용산구 도원동 29',
      autoRoadAddress: '',
      autoJibunAddress: '서울 용산구 도원동 29',
      address: '서울 용산구 새창로 78',
      zonecode: '04356',
    })).toEqual([
      '서울 용산구 새창로 78',
      '서울 용산구 도원동 29',
    ])
  })

  it('도로명 주소가 없으면 지번 주소로 좌표를 다시 조회한다', async () => {
    const addressSearch = mockGeocoder({
      '서울 용산구 새창로 78': { status: 'ZERO_RESULT' },
      '서울 용산구 도원동 29': { status: 'OK', x: '126.956', y: '37.539' },
    })

    await expect(geocodeAddress([
      '서울 용산구 새창로 78',
      '서울 용산구 도원동 29',
    ])).resolves.toEqual({ latitude: '37.539', longitude: '126.956' })
    expect(addressSearch.mock.calls.map(([address]) => address)).toEqual([
      '서울 용산구 새창로 78',
      '서울 용산구 도원동 29',
    ])
  })

  it('서비스 오류에는 다른 주소를 재시도하지 않는다', async () => {
    const addressSearch = mockGeocoder({
      '서울 용산구 새창로 78': { status: 'ERROR' },
    })

    await expect(geocodeAddress([
      '서울 용산구 새창로 78',
      '서울 용산구 도원동 29',
    ])).rejects.toThrow('좌표 변환 서비스에 문제가 발생했습니다')
    expect(addressSearch).toHaveBeenCalledTimes(1)
  })
})
