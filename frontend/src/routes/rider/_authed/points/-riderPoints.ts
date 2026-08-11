import { isAxiosError } from 'axios'
import type {
  GetRiderPointTransactionsType,
  PointTransactionResponse,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { formatSeoul, getSeoulYearMonth } from '@/shared/lib/datetime'

export type PointFilter = 'ALL' | GetRiderPointTransactionsType
export type PointInfoTab = 'charge-account' | 'withdrawal-account' | 'guide'
export type WithdrawalFormValues = {
  amount: number
  bankCode: string
  accountNumber: string
  accountHolderName: string
}

export const MIN_WITHDRAWAL_AMOUNT = 5_000

export const withdrawalBankOptions = [
  { code: '004', name: '과거은행' },
  { code: '088', name: '근미래은행' },
  { code: '020', name: '아프로은행' },
  { code: '081', name: '우와은행' },
  { code: '011', name: '성신은행' },
] as const

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
  // 거래 시각은 한국 시간 기준으로 월을 판정한다. selectedMonth 는 화면이 로컬 Date 로 만든
  // "보고 있는 달" 표식이라 KST 브라우저(배포 환경)에서 로컬=서울로 일치한다.
  const ym = getSeoulYearMonth(value)
  if (!ym) return false
  return ym.year === selectedMonth.getFullYear() && ym.month === selectedMonth.getMonth()
}

export function formatPointTransactionDate(value?: string): string {
  return (
    formatSeoul(value, {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }) ?? '거래 시각 미제공'
  )
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

/**
 * WITHDRAWAL·WITHDRAWAL_REFUND 행에만 붙는 처리 상태 배지. 그 외 유형은 이 필드가 없어(백엔드
 * PointTransactionResponse#withdrawalStatus 계약) null 을 돌려주고, 화면은 배지를 아예 그리지 않는다.
 *
 * WITHDRAWAL_REFUND 는 그 출금이 실패해 포인트가 돌아왔다는 뜻이라 항상 FAILED 로만 온다 — 그래도
 * 라벨은 "실패"로 통일해 WITHDRAWAL 행의 실패 상태와 같은 문구를 쓰게 한다(#90 후속).
 */
export function getWithdrawalStatusLabel(
  status?: PointTransactionResponse['withdrawalStatus'],
): string | null {
  switch (status) {
    case 'PENDING':
      return '처리 대기'
    case 'COMPLETED':
      return '완료'
    case 'FAILED':
      return '실패'
    default:
      return null
  }
}

export function getWithdrawalStatusClassName(
  status?: PointTransactionResponse['withdrawalStatus'],
): string {
  switch (status) {
    case 'PENDING':
      return 'bg-secondary-container text-on-secondary-container'
    case 'FAILED':
      return 'bg-error-container text-on-error-container'
    case 'COMPLETED':
    default:
      return 'bg-tertiary-container text-on-tertiary-container'
  }
}

export function getSignedPointAmount(item: PointTransactionResponse): number {
  return (item.direction === 'DEBIT' ? -1 : 1) * (item.amount ?? 0)
}

export function getWithdrawalValidation(
  values: { amount: string; bankCode: string; accountNumber: string; accountHolderName: string },
  balance: number,
): string | null {
  const amount = Number(values.amount)
  if (!Number.isInteger(amount) || amount < MIN_WITHDRAWAL_AMOUNT) {
    return `출금 금액은 ${formatPoints(MIN_WITHDRAWAL_AMOUNT)} 이상이어야 합니다.`
  }
  if (amount > balance) return '출금 가능 포인트를 초과했습니다.'
  if (!withdrawalBankOptions.some((bank) => bank.code === values.bankCode)) return '은행을 선택해 주세요.'
  if (!/^\d{6,20}$/.test(values.accountNumber)) return '계좌번호는 6~20자리 숫자로 입력해 주세요.'
  if (!values.accountHolderName.trim()) return '예금주명을 입력해 주세요.'
  if (values.accountHolderName.trim().length > 50) return '예금주명은 50자 이내로 입력해 주세요.'
  return null
}

export function getPointErrorMessage(error: unknown): string {
  const fallback = '포인트 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
  if (!isAxiosError<{ message?: string }>(error)) return fallback
  if (!error.response) return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  return error.response.data?.message || fallback
}
