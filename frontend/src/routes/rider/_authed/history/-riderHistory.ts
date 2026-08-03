import { isAxiosError } from 'axios'

export function formatSettlementAmount(amount?: number): string {
  return `${(amount ?? 0).toLocaleString('ko-KR')}원`
}

export function formatSettlementDate(settledAt?: string): string {
  if (!settledAt) {
    return '정산 시각 미제공'
  }

  const date = new Date(settledAt)
  if (Number.isNaN(date.getTime())) {
    return '정산 시각 미제공'
  }

  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

export function getSettlementErrorMessage(error: unknown): string {
  const defaultMessage = '배송 이력을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

  if (!isAxiosError<{ message?: string }>(error)) {
    return defaultMessage
  }
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  }
  return error.response.data?.message || defaultMessage
}
