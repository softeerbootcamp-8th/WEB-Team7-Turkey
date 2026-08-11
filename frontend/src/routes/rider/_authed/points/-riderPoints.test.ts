import { describe, expect, it } from 'vitest'
import {
  formatPoints,
  getPointTransactionLabel,
  getWithdrawalValidation,
  getSignedPointAmount,
  isSamePointMonth,
  shiftPointMonth,
  withdrawalBankOptions,
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

describe('출금 신청 입력 검증', () => {
  const valid = {
    amount: '5000',
    bankCode: '004',
    accountNumber: '12345678901234',
    accountHolderName: '홍길동',
  }

  it('백엔드 계약에 맞는 계좌정보를 허용한다', () => {
    expect(getWithdrawalValidation(valid, 10_000)).toBeNull()
  })

  it('화면의 은행명을 표준 은행 코드에 매핑한다', () => {
    expect(withdrawalBankOptions).toEqual([
      { code: '004', name: '과거은행' },
      { code: '088', name: '근미래은행' },
      { code: '020', name: '아프로은행' },
      { code: '081', name: '우와은행' },
      { code: '011', name: '성신은행' },
    ])
  })

  it('목록에 없는 은행 코드는 허용하지 않는다', () => {
    expect(getWithdrawalValidation({ ...valid, bankCode: '999' }, 10_000)).toBe('은행을 선택해 주세요.')
  })

  it('최소 출금액과 잔액을 검증한다', () => {
    expect(getWithdrawalValidation({ ...valid, amount: '4999' }, 10_000)).toContain('5,000P')
    expect(getWithdrawalValidation({ ...valid, amount: '10001' }, 10_000)).toBe('출금 가능 포인트를 초과했습니다.')
  })

  it('계좌번호를 6~20자리 숫자로 제한한다', () => {
    expect(getWithdrawalValidation({ ...valid, accountNumber: '123-456' }, 10_000)).toContain('6~20자리')
    expect(getWithdrawalValidation({ ...valid, accountNumber: '12345' }, 10_000)).toContain('6~20자리')
  })
})
