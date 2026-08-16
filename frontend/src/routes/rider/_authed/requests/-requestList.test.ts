import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import type { RiderDeliveryRequestSummaryResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import {
  buildNextRequestCursor,
  formatDistance,
  formatItemType,
  formatRequestedAt,
  formatSettlement,
  getPositionUnavailableGuidance,
  getPositionUnavailableSummary,
  getRequestListErrorMessage,
  requestRiderPosition,
} from './-requestList'

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

describe('콜 목록 표시값', () => {
  it('미터와 킬로미터 단위로 거리를 표시한다', () => {
    expect(formatDistance(758)).toBe('758m')
    expect(formatDistance(3200)).toBe('3.2km')
    expect(formatDistance(undefined)).toBe('거리 정보 없음')
    expect(formatDistance(undefined, '내 위치 정보 없음')).toBe('내 위치 정보 없음')
  })

  it('물품 종류와 예상 정산액을 사용자 문구로 표시한다', () => {
    expect(formatItemType('MEDIUM_PARCEL')).toBe('중형')
    expect(formatItemType(undefined)).toBe('물품 정보 없음')
    expect(formatSettlement(6860)).toBe('6,860P')
    expect(formatSettlement(undefined)).toBe('금액 미정')
  })

  it('유효한 요청 시각만 표시한다', () => {
    expect(formatRequestedAt(undefined)).toBeNull()
    expect(formatRequestedAt('invalid')).toBeNull()
    expect(formatRequestedAt('2026-08-03T04:00:00Z')).not.toBeNull()
  })
})

describe('다음 페이지 커서 생성(#509)', () => {
  it('sort=DISTANCE, 좌표가 있으면 afterDistanceMeters + afterId를 채운다', () => {
    const lastItem: RiderDeliveryRequestSummaryResponse = {
      deliveryId: 42,
      distanceToPickupMeters: 850,
      requestedAt: '2026-08-13T01:00:00Z',
    }

    expect(buildNextRequestCursor('DISTANCE', true, lastItem)).toEqual({ afterDistanceMeters: 850, afterId: 42 })
  })

  it('sort=DISTANCE, 좌표가 없으면(백엔드가 REQUESTED_AT으로 대체) afterRequestedAt + afterId를 채운다', () => {
    const lastItem: RiderDeliveryRequestSummaryResponse = {
      deliveryId: 7,
      distanceToPickupMeters: undefined,
      requestedAt: '2026-08-13T01:00:00Z',
    }

    expect(buildNextRequestCursor('DISTANCE', false, lastItem))
      .toEqual({ afterRequestedAt: '2026-08-13T01:00:00Z', afterId: 7 })
  })

  it('sort=FARE는 좌표 유무와 무관하게 afterFare + afterId를 채운다(#510/#522)', () => {
    const lastItem: RiderDeliveryRequestSummaryResponse = {
      deliveryId: 9,
      expectedSettlementAmount: 12000,
    }

    expect(buildNextRequestCursor('FARE', false, lastItem)).toEqual({ afterFare: 12000, afterId: 9 })
    expect(buildNextRequestCursor('FARE', true, lastItem)).toEqual({ afterFare: 12000, afterId: 9 })
  })

  it('sort=DELIVERY_DISTANCE는 좌표 유무와 무관하게 afterDeliveryDistanceMeters + afterId를 채운다(#510/#522)', () => {
    const lastItem: RiderDeliveryRequestSummaryResponse = {
      deliveryId: 11,
      straightDistanceMeters: 3200,
    }

    expect(buildNextRequestCursor('DELIVERY_DISTANCE', false, lastItem))
      .toEqual({ afterDeliveryDistanceMeters: 3200, afterId: 11 })
  })

  it('마지막 항목이 없으면(빈 페이지) 커서를 만들지 않는다', () => {
    expect(buildNextRequestCursor('DISTANCE', true, undefined)).toBeUndefined()
  })

  it('deliveryId가 없으면 커서를 만들지 않는다', () => {
    expect(buildNextRequestCursor('DISTANCE', true, { deliveryId: undefined, distanceToPickupMeters: 100 }))
      .toBeUndefined()
  })

  it('sort=DISTANCE, 좌표가 있는데 거리값이 없으면(방어적) 커서를 만들지 않는다', () => {
    expect(buildNextRequestCursor('DISTANCE', true, { deliveryId: 1, distanceToPickupMeters: undefined }))
      .toBeUndefined()
  })

  it('sort=DISTANCE, 좌표가 없는데 요청 시각이 없으면(방어적) 커서를 만들지 않는다', () => {
    expect(buildNextRequestCursor('DISTANCE', false, { deliveryId: 1, requestedAt: undefined })).toBeUndefined()
  })

  it('sort=FARE인데 예상 정산액이 없으면(방어적) 커서를 만들지 않는다', () => {
    expect(buildNextRequestCursor('FARE', false, { deliveryId: 1, expectedSettlementAmount: undefined }))
      .toBeUndefined()
  })

  it('sort=DELIVERY_DISTANCE인데 배송거리가 없으면(방어적) 커서를 만들지 않는다', () => {
    expect(buildNextRequestCursor('DELIVERY_DISTANCE', false, { deliveryId: 1, straightDistanceMeters: undefined }))
      .toBeUndefined()
  })
})

describe('목록 조회 오류 문구', () => {
  it('서버 메시지를 우선 표시한다', () => {
    expect(getRequestListErrorMessage(httpError(400, '조회 반경이 올바르지 않습니다.'))).toBe(
      '조회 반경이 올바르지 않습니다.',
    )
  })

  it('네트워크 오류를 구분한다', () => {
    expect(getRequestListErrorMessage(new AxiosError('Network Error', AxiosError.ERR_NETWORK))).toBe(
      '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
    )
  })
})

describe('라이더 좌표 조회(#496)', () => {
  it('위치 조회 성공 시 좌표를 담아 resolve한다', async () => {
    const geolocation: Pick<Geolocation, 'getCurrentPosition'> = {
      getCurrentPosition: vi.fn((success) => {
        success({
          coords: { latitude: 37.5, longitude: 127.0 },
        } as GeolocationPosition)
      }),
    }

    await expect(requestRiderPosition(geolocation)).resolves.toEqual({ latitude: 37.5, longitude: 127.0 })
  })

  it('권한 거부 시 사유(PERMISSION_DENIED)와 함께 resolve한다(예외를 던지지 않음)', async () => {
    const geolocation: Pick<Geolocation, 'getCurrentPosition'> = {
      getCurrentPosition: vi.fn((_success, error) => {
        error?.({ code: 1, message: 'denied' } as GeolocationPositionError)
      }),
    }

    await expect(requestRiderPosition(geolocation)).resolves.toEqual({ unavailableReason: 'PERMISSION_DENIED' })
  })

  it('타임아웃 시 사유(TIMEOUT)와 함께 resolve한다', async () => {
    const geolocation: Pick<Geolocation, 'getCurrentPosition'> = {
      getCurrentPosition: vi.fn((_success, error) => {
        error?.({ code: 3, message: 'timeout' } as GeolocationPositionError)
      }),
    }

    await expect(requestRiderPosition(geolocation)).resolves.toEqual({ unavailableReason: 'TIMEOUT' })
  })

  it('그 외 실패는 POSITION_UNAVAILABLE로 뭉뚱그린다', async () => {
    const geolocation: Pick<Geolocation, 'getCurrentPosition'> = {
      getCurrentPosition: vi.fn((_success, error) => {
        error?.({ code: 2, message: 'unavailable' } as GeolocationPositionError)
      }),
    }

    await expect(requestRiderPosition(geolocation)).resolves.toEqual({ unavailableReason: 'POSITION_UNAVAILABLE' })
  })

  it('geolocation 자체를 지원하지 않으면 UNSUPPORTED 사유로 resolve한다', async () => {
    await expect(requestRiderPosition(undefined)).resolves.toEqual({ unavailableReason: 'UNSUPPORTED' })
  })
})

describe('좌표 조회 실패 안내 문구(#496)', () => {
  it('안드로이드 앱에서는 휴대폰 설정 경로를 안내한다', () => {
    expect(getPositionUnavailableGuidance('android')).toContain('휴대폰 설정')
  })

  it('그 외(브라우저 등)에서는 사이트 위치 권한을 안내한다', () => {
    expect(getPositionUnavailableGuidance('web')).toContain('브라우저')
  })

  it('사유별로 다른 기본 안내를 보여주되, 권한 거부/시스템 설정을 특정해 단정하지 않는다', () => {
    // PERMISSION_DENIED(code 1)는 브라우저·OS에 따라 시스템 위치 서비스 꺼짐까지 뭉쳐서 오는
    // 경우가 실측으로 확인돼(#496), "이 사이트/앱의 권한"처럼 원인을 특정하지 않는다.
    expect(getPositionUnavailableSummary('PERMISSION_DENIED')).not.toMatch(/사이트|앱/)
    expect(getPositionUnavailableSummary('TIMEOUT')).toContain('시간')
    expect(getPositionUnavailableSummary('POSITION_UNAVAILABLE')).not.toMatch(/사이트|앱/)
    expect(getPositionUnavailableSummary(undefined)).toBe('위치 정보를 확인할 수 없어요.')
  })
})
