import { isAxiosError } from 'axios'
import type {
  RiderDeliveryHistoryItemResponse,
  RiderDeliveryHistoryItemResponseItemType,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { formatSeoul } from '@/shared/lib/datetime'

export interface RiderDeliveryHistoryItem {
  deliveryId?: number
  completedAt?: string
  status: 'COMPLETED'
  itemType?: RiderDeliveryHistoryItemResponseItemType
  pickupRoadAddress?: string
  destinationRoadAddress?: string
  straightDistanceMeters?: number
}

const ITEM_TYPE_LABELS: Record<RiderDeliveryHistoryItemResponseItemType, string> = {
  DOCUMENT: '문서',
  SMALL_PARCEL: '소형',
  MEDIUM_PARCEL: '중형',
  LARGE_PARCEL: '대형',
  FOOD: '음식',
}

/**
 * 운행 기록 응답을 화면 모델로 옮긴다. 운행 기록은 완료 배송만 담으므로 상태는 COMPLETED 고정이다.
 * 목록 카드가 쓰는 필드(식별자·완료 시각·출발/도착 주소·물품·거리)만 추린다 — 금액(운임·정산액)은
 * 이 API 응답에 없다(포인트 화면 소관).
 */
export function toRiderDeliveryHistory(items: RiderDeliveryHistoryItemResponse[]): RiderDeliveryHistoryItem[] {
  return items.map((item) => ({
    deliveryId: item.deliveryId,
    completedAt: item.completedAt,
    status: 'COMPLETED',
    itemType: item.itemType,
    pickupRoadAddress: item.pickupRoadAddress,
    destinationRoadAddress: item.destinationRoadAddress,
    straightDistanceMeters: item.straightDistanceMeters,
  }))
}

export function formatHistoryDate(value?: string): string {
  return (
    formatSeoul(value, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      weekday: 'short',
    }) ?? '날짜 미제공'
  )
}

export function formatHistoryTime(value?: string): string {
  return (
    formatSeoul(value, {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }) ?? '완료 시각 미제공'
  )
}

export function formatItemType(itemType?: RiderDeliveryHistoryItemResponseItemType): string {
  if (!itemType) return ''
  return ITEM_TYPE_LABELS[itemType] ?? itemType
}

export function formatDistance(meters?: number): string {
  if (meters == null) return ''
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)}km` : `${meters}m`
}

export function getHistoryErrorMessage(error: unknown): string {
  const defaultMessage = '배송 기록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

  if (!isAxiosError<{ message?: string }>(error)) return defaultMessage
  if (!error.response) return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  return error.response.data?.message || defaultMessage
}
