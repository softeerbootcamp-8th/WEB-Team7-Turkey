import type { PointChargeRequestPaymentMethod } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

export const CHARGE_AMOUNT_OPTIONS = [10_000, 30_000, 50_000, 100_000] as const

export const PAYMENT_METHOD_OPTIONS: {
  value: PointChargeRequestPaymentMethod
  label: string
  description: string
  icon: string
}[] = [
  {
    value: 'CARD',
    label: '신용·체크카드',
    description: '등록 없이 카드로 결제합니다.',
    icon: 'credit_card',
  },
  {
    value: 'BANK_TRANSFER',
    label: '계좌이체',
    description: '은행 계좌에서 바로 이체합니다.',
    icon: 'account_balance',
  },
]

export function getChargeAmountError(amount: number): string | null {
  if (!Number.isSafeInteger(amount) || amount <= 0) return '충전 금액을 선택해 주세요.'
  if (amount < 1_000) return '최소 충전 금액은 1,000 P입니다.'
  if (amount > 1_000_000) return '한 번에 최대 1,000,000 P까지 충전할 수 있습니다.'
  if (amount % 1_000 !== 0) return '충전 금액은 1,000 P 단위로 선택해 주세요.'
  return null
}

export function addChargeAmount(currentAmount: number, increment: number): number {
  const safeCurrent = Number.isSafeInteger(currentAmount) && currentAmount > 0 ? currentAmount : 0
  return Math.min(1_000_000, safeCurrent + increment)
}

export function formatChargeAmountInput(value: string): string {
  if (!value) return ''
  const amount = Number(value)
  return Number.isSafeInteger(amount) ? amount.toLocaleString('ko-KR') : value
}

export function createMockPaymentAuthToken(): string {
  return `mock_auth_${crypto.randomUUID()}`
}
