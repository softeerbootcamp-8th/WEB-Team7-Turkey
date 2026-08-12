import { isAxiosError } from 'axios'
import type { PointTransactionResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { formatSeoul } from '@/shared/lib/datetime'

export function formatCustomerPoints(value?: number): string {
  return `${(value ?? 0).toLocaleString('ko-KR')} P`
}

export function getCustomerPointTransactionLabel(
  type?: PointTransactionResponse['transactionType'],
): string {
  switch (type) {
    case 'CHARGE':
      return '포인트 충전'
    case 'CHARGE_REFUND':
      return '충전 취소'
    case 'ORDER_USE':
      return '배송 결제'
    case 'ORDER_REFUND':
      return '배송 취소 환급'
    default:
      return '포인트 거래'
  }
}

export function getSignedCustomerPointAmount(item: PointTransactionResponse): number {
  return (item.direction === 'DEBIT' ? -1 : 1) * (item.amount ?? 0)
}

export function formatCustomerPointTransactionDate(value?: string): string {
  return (
    formatSeoul(value, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }) ?? '거래 시각 미제공'
  )
}

export function getCustomerPointTransactionReference(item: PointTransactionResponse): string | null {
  if (item.deliveryId != null) return `배송 #${item.deliveryId}`
  if (item.pointChargeId != null) return `충전 #${item.pointChargeId}`
  return null
}

export function getCustomerPointErrorMessage(error: unknown, fallback: string): string {
  if (!isAxiosError<{ message?: string }>(error)) return fallback
  if (!error.response) return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  return error.response.data?.message || fallback
}
