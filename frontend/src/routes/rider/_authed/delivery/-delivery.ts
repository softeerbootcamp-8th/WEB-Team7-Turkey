import { isAxiosError } from 'axios'
import type {
  AddressResponse,
  ApiResponseRiderDeliveryResponse,
  RiderDeliveryResponseStatus,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

interface DeliveryStageContent {
  title: string
  guide: string
  description: string
  statusLabel: string
  actionLabel: string
  navigationTarget: 'pickup' | 'destination'
}

const STAGE_CONTENT: Partial<Record<RiderDeliveryResponseStatus, DeliveryStageContent>> = {
  ASSIGNED: {
    title: '픽업지로 이동',
    guide: '픽업지로 출발해 주세요',
    description: '물품을 인수하러 출발할 준비가 되면 아래 버튼을 눌러 주세요.',
    statusLabel: '배차 완료',
    actionLabel: '픽업 출발하기',
    navigationTarget: 'pickup',
  },
  MOVING_TO_PICKUP: {
    title: '픽업 진행',
    guide: '물품을 픽업해 주세요',
    description: '픽업지에서 물품을 확인하고 인수한 뒤 완료해 주세요.',
    statusLabel: '픽업지 이동 중',
    actionLabel: '픽업 완료하기',
    navigationTarget: 'pickup',
  },
  PICKED_UP: {
    title: '배송 준비',
    guide: '배송지로 출발해 주세요',
    description: '물품 인수가 완료되었습니다. 배송지로 이동을 시작해 주세요.',
    statusLabel: '픽업 완료',
    actionLabel: '배송 출발하기',
    navigationTarget: 'destination',
  },
  DELIVERING: {
    title: '배송 진행',
    guide: '배송을 완료해 주세요',
    description: '배송지에 물품을 전달한 뒤 완료 인증을 등록해 주세요.',
    statusLabel: '배송 중',
    actionLabel: '배송 완료하기',
    navigationTarget: 'destination',
  },
}

export function getDeliveryStageContent(status?: RiderDeliveryResponseStatus): DeliveryStageContent | null {
  return status ? (STAGE_CONTENT[status] ?? null) : null
}

export function formatAddress(address?: AddressResponse): string {
  if (!address?.roadAddress) {
    return '주소 정보가 없습니다.'
  }
  return [address.roadAddress, address.detailAddress].filter(Boolean).join(' ')
}

export function formatDeliveryDistance(distanceMeters?: number): string {
  if (typeof distanceMeters !== 'number') {
    return '정보 미제공'
  }
  return distanceMeters < 1000
    ? `${distanceMeters.toLocaleString('ko-KR')}m`
    : `${(distanceMeters / 1000).toFixed(1)}km`
}

export function formatDeliveryAmount(amount?: number): string {
  return typeof amount === 'number' ? `${amount.toLocaleString('ko-KR')}원` : '정보 미제공'
}

export function getKakaoNavigationUrl(address?: AddressResponse): string | null {
  if (typeof address?.latitude !== 'number' || typeof address.longitude !== 'number') {
    return null
  }
  const name = encodeURIComponent(address.roadAddress || '목적지')
  return `https://map.kakao.com/link/to/${name},${address.latitude},${address.longitude}`
}

export function getDeliveryErrorMessage(error: unknown): string {
  return getApiErrorMessage(error, '진행 중 배송을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.')
}

export function getTransitionErrorMessage(error: unknown): string {
  return getApiErrorMessage(error, '배송 단계를 변경하지 못했습니다. 현재 상태를 확인해 주세요.')
}

function getApiErrorMessage(error: unknown, defaultMessage: string): string {
  if (!isAxiosError<ApiResponseRiderDeliveryResponse>(error)) {
    return defaultMessage
  }
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  }
  return error.response.data?.message || defaultMessage
}
