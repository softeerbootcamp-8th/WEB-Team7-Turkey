import { describe, expect, it } from 'vitest'
import type { DeliverySummaryResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import {
  formatDeliveryFare,
  formatDeliveryRequestedAt,
  getDeliveryListErrorMessage,
  partitionDeliveries,
} from './-deliveryList'

describe('delivery list', () => {
  it('진행 중 배송과 지난 배송을 구분한다', () => {
    const deliveries: DeliverySummaryResponse[] = [
      { deliveryId: 1, status: 'WAITING' },
      { deliveryId: 2, status: 'DELIVERING' },
      { deliveryId: 3, status: 'COMPLETED' },
      { deliveryId: 4, status: 'CANCELED' },
    ]
    const result = partitionDeliveries(deliveries)
    expect(result.active.map(item => item.deliveryId)).toEqual([1, 2])
    expect(result.past.map(item => item.deliveryId)).toEqual([3, 4])
  })

  it('날짜와 요금을 고객용 표기로 변환한다', () => {
    expect(formatDeliveryRequestedAt('2026-08-04T01:30:00Z')).toContain('2026')
    expect(formatDeliveryRequestedAt('invalid')).toBe('요청 시각 미제공')
    expect(formatDeliveryFare(12300)).toBe('12,300원')
    expect(formatDeliveryFare(undefined)).toBe('요금 미제공')
  })

  it('네트워크 오류와 일반 오류를 안내한다', () => {
    expect(getDeliveryListErrorMessage(new Error('failed'))).toContain('배송 내역')
  })
})
