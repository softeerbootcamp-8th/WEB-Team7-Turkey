import { isAxiosError } from 'axios'
import { Capacitor } from '@capacitor/core'
import type {
  AddressResponse,
  ApiResponseDeliveryCancelResponse,
  ApiResponseDeliveryDetailResponse,
  ContactResponse,
  DeliveryDetailResponseItemType,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

const ITEM_TYPE_LABELS: Record<DeliveryDetailResponseItemType, string> = {
  DOCUMENT: '문서',
  SMALL_PARCEL: '소형 소포',
  MEDIUM_PARCEL: '중형 소포',
  LARGE_PARCEL: '대형 소포',
  FOOD: '음식',
}

export type ProofImageEnvironment = 'development-web' | 'native' | 'production-web'

function currentProofImageEnvironment(): ProofImageEnvironment {
  if (Capacitor.isNativePlatform()) {
    return 'native'
  }
  return import.meta.env.DEV ? 'development-web' : 'production-web'
}

function localPathFromProofValue(value: string): string | null {
  if (/^[A-Za-z]:[\\/]/.test(value)) {
    return value.replaceAll('\\', '/')
  }
  if (/^\/(Users|home|private|tmp|var)\//.test(value)) {
    return value
  }
  if (value.startsWith('file://')) {
    try {
      return decodeURIComponent(new URL(value).pathname)
    } catch {
      return null
    }
  }
  return null
}

/**
 * S3/CDN URL과 웹 상대 URL은 그대로 사용한다. 로컬 절대경로는 네이티브 WebView 또는
 * 로컬 Vite 개발 서버에서만 변환하며, 배포 웹에서는 브라우저 보안상 읽을 수 없어 null을 반환한다.
 */
export function resolveProofImageSource(
  proofValue: string | null | undefined,
  environment: ProofImageEnvironment = currentProofImageEnvironment(),
): string | null {
  const value = proofValue?.trim()
  if (!value) {
    return null
  }

  const localPath = localPathFromProofValue(value)
  if (!localPath) {
    return value
  }
  if (environment === 'native') {
    return Capacitor.convertFileSrc(localPath)
  }
  if (environment === 'development-web') {
    return `/@fs${encodeURI(localPath)}`
  }
  return null
}

export function formatDetailItemType(itemType: string | null | undefined): string {
  if (itemType && itemType in ITEM_TYPE_LABELS) {
    return ITEM_TYPE_LABELS[itemType as DeliveryDetailResponseItemType]
  }
  return '물품 정보 미제공'
}

export function formatDetailAddress(address: AddressResponse | undefined): string {
  const parts = [address?.roadAddress, address?.detailAddress].filter(Boolean)
  return parts.length > 0 ? parts.join(' ') : '주소 미제공'
}

export function formatDetailContact(contact: ContactResponse | undefined): string {
  const parts = [contact?.name, contact?.phoneNumber].filter(Boolean)
  return parts.length > 0 ? parts.join(' · ') : '연락처 미제공'
}

export function formatDetailDateTime(value: string | undefined): string {
  if (!value) {
    return '시각 미제공'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '시각 미제공'
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

export function formatDetailFare(amount: number | undefined): string {
  return amount == null ? '요금 미제공' : `${amount.toLocaleString('ko-KR')}원`
}

export function formatDetailDistance(meters: number | undefined): string {
  if (meters == null) {
    return '거리 미제공'
  }
  return meters < 1000
    ? `${Math.round(meters).toLocaleString('ko-KR')}m`
    : `${(meters / 1000).toFixed(1)}km`
}

export type DetailErrorState = {
  notFound: boolean
  message: string
}

export function getDeliveryDetailErrorState(error: unknown): DetailErrorState {
  if (isAxiosError<ApiResponseDeliveryDetailResponse>(error) && error.response?.status === 404) {
    return {
      notFound: true,
      message: '배송요청을 찾을 수 없거나 접근할 수 없습니다.',
    }
  }
  if (isAxiosError(error) && !error.response) {
    return {
      notFound: false,
      message: '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
    }
  }
  return {
    notFound: false,
    message: '배송 상세 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
  }
}

export function getDeliveryCancelErrorMessage(error: unknown): string {
  if (isAxiosError<ApiResponseDeliveryCancelResponse>(error)) {
    if (error.response?.status === 409) {
      return '이미 배차되어 취소할 수 없습니다.'
    }
    if (error.response?.status === 404) {
      return '배송요청을 찾을 수 없거나 접근할 수 없습니다.'
    }
    if (!error.response) {
      return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
    }
    return error.response.data?.message || '배송요청을 취소하지 못했습니다.'
  }
  return '배송요청을 취소하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

export function isDeliveryCancelSuccess(response: ApiResponseDeliveryCancelResponse | undefined): boolean {
  return response?.success === true && response.data?.status === 'CANCELED'
}
