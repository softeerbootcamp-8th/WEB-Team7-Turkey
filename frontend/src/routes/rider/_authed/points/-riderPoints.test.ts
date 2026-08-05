import { describe, expect, it } from 'vitest'
import {
  formatPoints,
  getPointTransactionLabel,
  getSignedPointAmount,
  isSamePointMonth,
  shiftPointMonth,
} from './-riderPoints'

describe('포인트 내역 표시값', () => {
  it('입출금 방향에 따라 거래 금액의 부호를 만든다', () => {
    expect(getSignedPointAmount({ direction: 'CREDIT', amount: 12000 })).toBe(12000)
    expect(getSignedPointAmount({ direction: 'DEBIT', amount: 3000 })).toBe(-3000)
    expect(formatPoints(-3000)).toBe('-3,000P')
  })

  it('라이더 거래 유형을 사용자 문구로 표시한다', () => {
    expect(getPointTransactionLabel('SETTLEMENT')).toBe('배송 정산')
    expect(getPointTransactionLabel('WITHDRAWAL')).toBe('포인트 출금')
    expect(getPointTransactionLabel('WITHDRAWAL_REFUND')).toBe('출금 취소')
  })

  it('선택한 월을 이동하고 거래 월을 구분한다', () => {
    const august = new Date(2026, 7, 31)
    expect(shiftPointMonth(august, -1).getMonth()).toBe(6)
    expect(isSamePointMonth('2026-08-03T05:30:00Z', august)).toBe(true)
  })
})
