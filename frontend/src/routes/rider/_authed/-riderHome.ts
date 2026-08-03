import { isAxiosError } from 'axios'
import type { ApiResponseRiderOperatingStatusResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

export function getOperatingStatusErrorMessage(
  error: unknown,
  action: 'GO_ONLINE' | 'GO_OFFLINE',
): string {
  const defaultMessage = action === 'GO_ONLINE'
    ? '운행을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'
    : '운행을 종료하지 못했습니다. 잠시 후 다시 시도해 주세요.'

  if (!isAxiosError<ApiResponseRiderOperatingStatusResponse>(error)) {
    return defaultMessage
  }
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  }
  return error.response.data?.message || defaultMessage
}

export function getOperatingStatusQueryErrorMessage(error: unknown): string {
  const defaultMessage = '라이더 상태를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

  if (!isAxiosError<ApiResponseRiderOperatingStatusResponse>(error)) {
    return defaultMessage
  }
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  }
  return error.response.data?.message || defaultMessage
}
