import { isAxiosError } from 'axios'
import type {
  ApiResponseRiderDeliveryRequestAcceptResponse,
  ApiResponseRiderDeliveryRequestDetailResponse,
  RiderDeliveryRequestDetailResponseItemType,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { formatSeoul } from '@/shared/lib/datetime'

const ITEM_LABELS: Record<RiderDeliveryRequestDetailResponseItemType, string> = {
  DOCUMENT: '문서',
  SMALL_PARCEL: '소형',
  MEDIUM_PARCEL: '중형',
  LARGE_PARCEL: '대형',
  FOOD: '음식',
}

export type AcceptFailureKind = 'competition' | 'uncertain' | 'failure'

export function formatItemType(itemType: RiderDeliveryRequestDetailResponseItemType | undefined): string {
  return itemType ? ITEM_LABELS[itemType] : '물품 정보 없음'
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

export function formatMoney(amount: number | undefined): string {
  return amount == null ? '금액 미정' : `${amount.toLocaleString('ko-KR')}P`
}

export function formatMinutes(minutes: number | undefined): string {
  return minutes == null ? '예상 시간 없음' : `약 ${minutes}분`
}

export function formatRequestedAt(requestedAt: string | undefined): string | null {
  return formatSeoul(requestedAt, {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function getDetailErrorMessage(error: unknown): string {
  if (!isAxiosError<ApiResponseRiderDeliveryRequestDetailResponse>(error)) {
    return '콜 상세 정보를 불러오지 못했습니다.'
  }
  if (error.response?.status === 404) {
    return '이미 배차되었거나 취소된 콜입니다.'
  }
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  }
  return error.response.data?.message || '콜 상세 정보를 불러오지 못했습니다.'
}

export function classifyAcceptFailure(error: unknown): AcceptFailureKind {
  if (!isAxiosError(error)) {
    return 'uncertain'
  }
  if (error.response?.status === 409) {
    return 'competition'
  }
  if (!error.response || error.response.status >= 500) {
    return 'uncertain'
  }
  return 'failure'
}

export function getAcceptErrorMessage(error: unknown): string {
  if (!isAxiosError<ApiResponseRiderDeliveryRequestAcceptResponse>(error)) {
    return '배차 결과를 확인하지 못했습니다. 다시 확인해 주세요.'
  }
  return error.response?.data?.message || '콜을 수락하지 못했습니다. 다시 시도해 주세요.'
}
