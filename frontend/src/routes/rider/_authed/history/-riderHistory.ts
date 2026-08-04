import { isAxiosError } from 'axios'
import type { SettlementResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

export interface RiderDeliveryHistoryItem {
  deliveryId?: number
  completedAt?: string
  status: 'COMPLETED'
}

const SEOUL_TIME_ZONE = 'Asia/Seoul'

export function toRiderDeliveryHistory(items: SettlementResponse[]): RiderDeliveryHistoryItem[] {
  return items.map(({ deliveryId, settledAt }) => ({
    deliveryId,
    completedAt: settledAt,
    status: 'COMPLETED',
  }))
}

export function formatHistoryDay(date: Date): string {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: SEOUL_TIME_ZONE,
    month: 'long',
    day: 'numeric',
    weekday: 'short',
  }).format(date)
}

export function formatHistoryTime(value?: string): string {
  if (!value) return '완료 시각 미제공'

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '완료 시각 미제공'

  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: SEOUL_TIME_ZONE,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

export function isSameHistoryDay(value: string | undefined, selectedDate: Date): boolean {
  if (!value) return false
  const valueDate = new Date(value)
  if (Number.isNaN(valueDate.getTime())) return false

  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: SEOUL_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
  return formatter.format(valueDate) === formatter.format(selectedDate)
}

export function shiftHistoryDay(date: Date, amount: number): Date {
  const next = new Date(date)
  next.setDate(next.getDate() + amount)
  return next
}

export function getHistoryErrorMessage(error: unknown): string {
  const defaultMessage = '배송 기록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

  if (!isAxiosError<{ message?: string }>(error)) return defaultMessage
  if (!error.response) return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  return error.response.data?.message || defaultMessage
}
