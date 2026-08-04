export const CUSTOMER_DELIVERY_STATUS_LABELS = {
  WAITING: '배송 요청 접수',
  ASSIGNED: '라이더 배정',
  MOVING_TO_PICKUP: '픽업지로 이동 중',
  PICKED_UP: '픽업 완료',
  DELIVERING: '배송 중',
  COMPLETED: '배송 완료',
  CANCELED: '배송 취소',
} as const

export type DeliveryStatus = keyof typeof CUSTOMER_DELIVERY_STATUS_LABELS

export const ACTIVE_DELIVERY_STATUSES: ReadonlySet<string> = new Set([
  'WAITING',
  'ASSIGNED',
  'MOVING_TO_PICKUP',
  'PICKED_UP',
  'DELIVERING',
])

/** 백엔드 추적 스냅샷·SSE가 허용하는 배차 이후 ~ 완료 전 상태. */
export const TRACKABLE_DELIVERY_STATUSES: ReadonlySet<string> = new Set([
  'ASSIGNED',
  'MOVING_TO_PICKUP',
  'PICKED_UP',
  'DELIVERING',
])

export function getCustomerDeliveryStatusLabel(status: string | null | undefined): string {
  if (status && status in CUSTOMER_DELIVERY_STATUS_LABELS) {
    return CUSTOMER_DELIVERY_STATUS_LABELS[status as DeliveryStatus]
  }
  return '상태 확인 중'
}

export function isActiveDeliveryStatus(status: string | null | undefined): boolean {
  return status != null && ACTIVE_DELIVERY_STATUSES.has(status)
}

export function isTrackableDeliveryStatus(status: string | null | undefined): boolean {
  return status != null && TRACKABLE_DELIVERY_STATUSES.has(status)
}
