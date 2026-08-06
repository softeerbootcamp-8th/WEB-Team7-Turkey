import { isAxiosError } from 'axios'
import type {
  ApiResponseRiderDeliveryHistoryDetailResponse,
  DeliveryStatusStepResponse,
  DeliveryStatusStepResponseStatus,
  RiderDeliveryHistoryDetailResponseItemType,
  RiderDeliveryHistoryDetailResponseProofType,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

const SEOUL_TIME_ZONE = 'Asia/Seoul'

const ITEM_TYPE_LABELS: Record<RiderDeliveryHistoryDetailResponseItemType, string> = {
  DOCUMENT: '문서',
  SMALL_PARCEL: '소형',
  MEDIUM_PARCEL: '중형',
  LARGE_PARCEL: '대형',
  FOOD: '음식',
}

const STATUS_LABELS: Record<DeliveryStatusStepResponseStatus, string> = {
  WAITING: '접수 완료',
  ASSIGNED: '기사 배정',
  MOVING_TO_PICKUP: '픽업지 이동',
  PICKED_UP: '픽업 완료',
  DELIVERING: '배송 시작',
  COMPLETED: '배송 완료',
  CANCELED: '취소',
}

const PROOF_TYPE_LABELS: Record<RiderDeliveryHistoryDetailResponseProofType, string> = {
  PHOTO: '사진',
  RECIPIENT_CONFIRMATION: '수령인 확인',
  AUTH_CODE: '인증 코드',
}

export interface HistoryTimelineStep {
  status: DeliveryStatusStepResponseStatus
  label: string
  time: string
}

function toDate(value?: string): Date | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/** 헤더용 날짜(YYYY.MM.DD, Asia/Seoul). 값이 없거나 파싱 불가면 대체 문구. */
export function formatDetailDate(value?: string): string {
  const date = toDate(value)
  if (!date) return '날짜 미제공'
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: SEOUL_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
    .format(date)
    .replaceAll('-', '.')
}

/** 시각(HH:mm, 24시간, Asia/Seoul). 타임라인 각 단계와 헤더 완료 시각에 공용. */
export function formatDetailTime(value?: string): string {
  const date = toDate(value)
  if (!date) return '시각 미제공'
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: SEOUL_TIME_ZONE,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

export function formatItemType(itemType?: RiderDeliveryHistoryDetailResponseItemType): string {
  if (!itemType) return '물품 정보 없음'
  return ITEM_TYPE_LABELS[itemType] ?? itemType
}

export function formatDistance(meters?: number): string {
  if (meters == null) return '거리 정보 없음'
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)}km` : `${Math.round(meters).toLocaleString('ko-KR')}m`
}

export function formatMoney(amount?: number): string {
  return amount == null ? '금액 미정' : `${amount.toLocaleString('ko-KR')}원`
}

export function formatProofType(proofType?: RiderDeliveryHistoryDetailResponseProofType): string {
  if (!proofType) return '인증 정보 없음'
  return PROOF_TYPE_LABELS[proofType] ?? proofType
}

/**
 * 상태 전이 타임라인을 화면 단계로 옮긴다. 서버가 도달한 단계만(occurredAt 이 있는 것만) 시간 순으로
 * 내려주지만, 방어적으로 시각이 없는 항목은 걸러낸다. 라벨은 라이더 관점 문구로 매핑한다.
 */
export function toTimelineSteps(steps?: DeliveryStatusStepResponse[]): HistoryTimelineStep[] {
  if (!steps) return []
  return steps
    .filter((step): step is DeliveryStatusStepResponse & { status: DeliveryStatusStepResponseStatus } =>
      step.status != null && step.occurredAt != null,
    )
    .map((step) => ({
      status: step.status,
      label: STATUS_LABELS[step.status] ?? step.status,
      time: formatDetailTime(step.occurredAt),
    }))
}

/** 완료 시각(헤더 날짜·시각용). COMPLETED 단계가 없으면 정산 시각으로 보완. */
export function resolveCompletedAt(steps?: DeliveryStatusStepResponse[], settledAt?: string): string | undefined {
  const completed = steps?.find((step) => step.status === 'COMPLETED')
  return completed?.occurredAt ?? settledAt
}

/**
 * 상세 조회 실패 메시지. 존재하지 않거나 본인이 완료한 배송이 아니면 서버가 404 를 내므로
 * (두 경우를 구분하지 않는다 — 존재 여부 비노출) 목록으로 안내하는 전용 문구로 바꾼다.
 * 401 은 공용 인터셉터가 처리하므로 여기서 다루지 않는다.
 */
export function getDetailErrorMessage(error: unknown): string {
  const defaultMessage = '운행 상세 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
  if (!isAxiosError<ApiResponseRiderDeliveryHistoryDetailResponse>(error)) return defaultMessage
  if (error.response?.status === 404) {
    return '존재하지 않거나 접근할 수 없는 운행 기록입니다. 목록에서 다시 확인해 주세요.'
  }
  if (!error.response) return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  return error.response.data?.message || defaultMessage
}
