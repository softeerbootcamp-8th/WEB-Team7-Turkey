import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import type { RiderDeliveryRequestSummaryResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import {
  filterRequestsByItem,
  formatDistance,
  formatItemType,
  formatRequestedAt,
  formatSettlement,
  getRequestListErrorMessage,
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

describe('물품 크기 필터', () => {
  const requests: RiderDeliveryRequestSummaryResponse[] = [
    { deliveryId: 1, itemType: 'SMALL_PARCEL' },
    { deliveryId: 2, itemType: 'LARGE_PARCEL' },
    { deliveryId: 3, itemType: 'SMALL_PARCEL' },
  ]

  it('전체 선택 시 원본 목록을 반환한다', () => {
    expect(filterRequestsByItem(requests, 'ALL')).toBe(requests)
  })

  it('선택한 물품 종류만 남긴다', () => {
    expect(filterRequestsByItem(requests, 'SMALL_PARCEL').map((request) => request.deliveryId)).toEqual([1, 3])
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
