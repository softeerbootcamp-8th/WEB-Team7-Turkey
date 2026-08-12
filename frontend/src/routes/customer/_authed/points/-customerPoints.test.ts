import { describe, expect, it } from 'vitest'
import {
  formatCustomerPoints,
  getCustomerPointTransactionLabel,
  getCustomerPointTransactionReference,
  getSignedCustomerPointAmount,
} from './-customerPoints'

describe('고객 포인트 내역 표시값', () => {
  it('거래 방향에 따라 금액 부호를 표시한다', () => {
    expect(getSignedCustomerPointAmount({ direction: 'CREDIT', amount: 12_000 })).toBe(12_000)
    expect(getSignedCustomerPointAmount({ direction: 'DEBIT', amount: 3_000 })).toBe(-3_000)
    expect(formatCustomerPoints(-3_000)).toBe('-3,000 P')
  })

  it('고객 거래 유형을 화면 문구로 바꾼다', () => {
    expect(getCustomerPointTransactionLabel('CHARGE')).toBe('포인트 충전')
    expect(getCustomerPointTransactionLabel('CHARGE_REFUND')).toBe('충전 취소')
    expect(getCustomerPointTransactionLabel('ORDER_USE')).toBe('배송 결제')
    expect(getCustomerPointTransactionLabel('ORDER_REFUND')).toBe('배송 취소 환급')
  })

  it('거래의 연관 대상을 표시한다', () => {
    expect(getCustomerPointTransactionReference({ deliveryId: 42 })).toBe('배송 #42')
    expect(getCustomerPointTransactionReference({ pointChargeId: 7 })).toBe('충전 #7')
    expect(getCustomerPointTransactionReference({})).toBeNull()
  })
})
