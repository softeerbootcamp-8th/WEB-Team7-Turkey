import { isAxiosError } from 'axios'
import type {
  ApiResponseListRiderDeliveryRequestSummaryResponse,
  RiderDeliveryRequestSummaryResponse,
  RiderDeliveryRequestSummaryResponseItemType,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { formatSeoul } from '@/shared/lib/datetime'

export const DEFAULT_RADIUS_METERS = 3000

export const RADIUS_OPTIONS = [
  { value: 1000, label: '1km' },
  { value: 3000, label: '3km' },
  { value: 5000, label: '5km' },
  { value: 10000, label: '10km' },
] as const

export type ItemFilter = 'ALL' | RiderDeliveryRequestSummaryResponseItemType

export const ITEM_FILTER_OPTIONS: ReadonlyArray<{ value: ItemFilter; label: string }> = [
  { value: 'ALL', label: '전체' },
  { value: 'DOCUMENT', label: '문서' },
  { value: 'SMALL_PARCEL', label: '소형' },
  { value: 'MEDIUM_PARCEL', label: '중형' },
  { value: 'LARGE_PARCEL', label: '대형' },
  { value: 'FOOD', label: '음식' },
]

const ITEM_TYPE_LABELS: Record<RiderDeliveryRequestSummaryResponseItemType, string> = {
  DOCUMENT: '문서',
  SMALL_PARCEL: '소형',
  MEDIUM_PARCEL: '중형',
  LARGE_PARCEL: '대형',
  FOOD: '음식',
}

export function filterRequestsByItem(
  requests: RiderDeliveryRequestSummaryResponse[],
  itemFilter: ItemFilter,
): RiderDeliveryRequestSummaryResponse[] {
  if (itemFilter === 'ALL') {
    return requests
  }
  return requests.filter((request) => request.itemType === itemFilter)
}

export function formatItemType(itemType: RiderDeliveryRequestSummaryResponseItemType | undefined): string {
  return itemType ? ITEM_TYPE_LABELS[itemType] : '물품 정보 없음'
}

export function formatDistance(meters: number | undefined): string {
  if (meters == null) {
    return '거리 정보 없음'
  }
  if (meters < 1000) {
    return `${Math.round(meters).toLocaleString('ko-KR')}m`
  }
  return `${(meters / 1000).toFixed(1)}km`
}

export function formatSettlement(amount: number | undefined): string {
  return amount == null ? '금액 미정' : `${amount.toLocaleString('ko-KR')}P`
}

export function formatRequestedAt(requestedAt: string | undefined): string | null {
  return formatSeoul(requestedAt, { hour: '2-digit', minute: '2-digit' })
}

export function getRequestListErrorMessage(error: unknown): string {
  if (!isAxiosError<ApiResponseListRiderDeliveryRequestSummaryResponse>(error)) {
    return '콜 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  }
  return error.response.data?.message || '콜 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}
