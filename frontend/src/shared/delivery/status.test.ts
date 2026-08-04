import { describe, expect, it } from 'vitest'
import { getCustomerDeliveryStatusLabel, isActiveDeliveryStatus } from './status'

describe('customer delivery status', () => {
  it.each([
    ['WAITING', '배송 요청 접수'],
    ['ASSIGNED', '라이더 배정'],
    ['MOVING_TO_PICKUP', '픽업지로 이동 중'],
    ['PICKED_UP', '픽업 완료'],
    ['DELIVERING', '배송 중'],
    ['COMPLETED', '배송 완료'],
    ['CANCELED', '배송 취소'],
  ])('%s 상태를 고객용 문구로 바꾼다', (status, label) => {
    expect(getCustomerDeliveryStatusLabel(status)).toBe(label)
  })

  it('알 수 없는 상태는 기본 문구로 표시한다', () => {
    expect(getCustomerDeliveryStatusLabel('RETURNED')).toBe('상태 확인 중')
    expect(getCustomerDeliveryStatusLabel(undefined)).toBe('상태 확인 중')
  })

  it('진행 중과 종료 상태를 구분한다', () => {
    expect(isActiveDeliveryStatus('WAITING')).toBe(true)
    expect(isActiveDeliveryStatus('DELIVERING')).toBe(true)
    expect(isActiveDeliveryStatus('COMPLETED')).toBe(false)
    expect(isActiveDeliveryStatus('CANCELED')).toBe(false)
    expect(isActiveDeliveryStatus('UNKNOWN')).toBe(false)
  })
})
