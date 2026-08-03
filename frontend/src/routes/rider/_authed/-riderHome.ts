import { isAxiosError } from 'axios'
import type { ApiResponseRiderOperatingStatusResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

export function getGoOnlineErrorMessage(error: unknown): string {
  if (!isAxiosError<ApiResponseRiderOperatingStatusResponse>(error)) {
    return '운행을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  }
  return error.response.data?.message || '운행을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}
