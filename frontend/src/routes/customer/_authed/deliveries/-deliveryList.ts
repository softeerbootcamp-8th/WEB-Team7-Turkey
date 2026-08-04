import { isAxiosError } from 'axios'
import type {
  ApiResponseDeliveryListResponse,
  DeliverySummaryResponse,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { isActiveDeliveryStatus } from '@/shared/delivery/status'

export function partitionDeliveries(deliveries: DeliverySummaryResponse[]): {
  active: DeliverySummaryResponse[]
  past: DeliverySummaryResponse[]
} {
  return deliveries.reduce<{ active: DeliverySummaryResponse[]; past: DeliverySummaryResponse[] }>(
    (result, delivery) => {
      result[isActiveDeliveryStatus(delivery.status) ? 'active' : 'past'].push(delivery)
      return result
    },
    { active: [], past: [] },
  )
}

export function formatDeliveryRequestedAt(requestedAt: string | undefined): string {
  if (!requestedAt) {
    return '요청 시각 미제공'
  }
  const date = new Date(requestedAt)
  if (Number.isNaN(date.getTime())) {
    return '요청 시각 미제공'
  }
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

export function formatDeliveryFare(totalFare: number | undefined): string {
  return totalFare == null ? '요금 미제공' : `${totalFare.toLocaleString('ko-KR')}원`
}

export function getDeliveryListErrorMessage(error: unknown): string {
  const fallback = '배송 내역을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
  if (!isAxiosError<ApiResponseDeliveryListResponse>(error)) {
    return fallback
  }
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  }
  return error.response.data?.message || fallback
}
