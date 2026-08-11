import { describe, expect, it } from 'vitest'
import { addChargeAmount, formatChargeAmountInput, getChargeAmountError } from './-charge'

describe('포인트 충전 금액 검증', () => {
  it('서버 정책 범위의 1,000P 단위 금액을 허용한다', () => {
    expect(getChargeAmountError(1_000)).toBeNull()
    expect(getChargeAmountError(30_000)).toBeNull()
    expect(getChargeAmountError(1_000_000)).toBeNull()
  })

  it('범위 밖이거나 단위가 잘못된 금액을 거부한다', () => {
    expect(getChargeAmountError(0)).toBe('충전 금액을 선택해 주세요.')
    expect(getChargeAmountError(999)).toBe('최소 충전 금액은 1,000 P입니다.')
    expect(getChargeAmountError(1_500)).toBe('충전 금액은 1,000 P 단위로 선택해 주세요.')
    expect(getChargeAmountError(1_001_000)).toBe('한 번에 최대 1,000,000 P까지 충전할 수 있습니다.')
  })

  it('금액 버튼을 누를 때마다 입력 금액을 누적하고 최대 금액에서 멈춘다', () => {
    expect(addChargeAmount(0, 10_000)).toBe(10_000)
    expect(addChargeAmount(10_000, 10_000)).toBe(20_000)
    expect(addChargeAmount(950_000, 100_000)).toBe(1_000_000)
  })

  it('입력 금액을 천 단위 콤마로 표시한다', () => {
    expect(formatChargeAmountInput('')).toBe('')
    expect(formatChargeAmountInput('10000')).toBe('10,000')
    expect(formatChargeAmountInput('1000000')).toBe('1,000,000')
  })
})
