import { isAxiosError } from 'axios'
import type {
  GetRiderPointTransactionsType,
  PointTransactionResponse,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

export type PointFilter = 'ALL' | GetRiderPointTransactionsType
export type PointInfoTab = 'charge-account' | 'withdrawal-account' | 'guide'

export const pointFilterOptions: { value: PointFilter; label: string }[] = [
  { value: 'ALL', label: '전체' },
  { value: 'SETTLEMENT', label: '정산' },
  { value: 'WITHDRAWAL', label: '출금' },
  { value: 'WITHDRAWAL_REFUND', label: '출금 취소' },
]

export function formatPoints(value?: number): string {
  return `${(value ?? 0).toLocaleString('ko-KR')}P`
}

export function formatPointMonth(date: Date): string {
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
  }).format(date)
}

export function shiftPointMonth(date: Date, amount: number): Date {
  const next = new Date(date)
  next.setDate(1)
  next.setMonth(next.getMonth() + amount)
  return next
}

export function isSamePointMonth(value: string | undefined, selectedMonth: Date): boolean {
  if (!value) return false
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return false
  return date.getFullYear() === selectedMonth.getFullYear() && date.getMonth() === selectedMonth.getMonth()
}

export function formatPointTransactionDate(value?: string): string {
  if (!value) return '거래 시각 미제공'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '거래 시각 미제공'
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

export function getPointTransactionLabel(type?: PointTransactionResponse['transactionType']): string {
  switch (type) {
    case 'SETTLEMENT':
      return '배송 정산'
    case 'WITHDRAWAL':
      return '포인트 출금'
    case 'WITHDRAWAL_REFUND':
      return '출금 취소'
    default:
      return '포인트 거래'
  }
}

export function getSignedPointAmount(item: PointTransactionResponse): number {
  return (item.direction === 'DEBIT' ? -1 : 1) * (item.amount ?? 0)
}

export function getPointErrorMessage(error: unknown): string {
  const fallback = '포인트 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
  if (!isAxiosError<{ message?: string }>(error)) return fallback
  if (!error.response) return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  return error.response.data?.message || fallback
}
