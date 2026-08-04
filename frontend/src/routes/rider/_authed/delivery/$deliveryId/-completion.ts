import { isAxiosError } from 'axios'
import type { ApiResponseRiderDeliveryCompleteResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

export const MAX_COMPLETION_PHOTO_BYTES = 50 * 1024 * 1024

export function validateCompletionPhoto(file: Pick<File, 'size' | 'type'>): string | null {
  if (!file.type.startsWith('image/')) {
    return '사진 파일만 선택할 수 있습니다.'
  }
  if (file.size === 0) {
    return '비어 있는 사진은 사용할 수 없습니다. 다시 촬영해 주세요.'
  }
  if (file.size > MAX_COMPLETION_PHOTO_BYTES) {
    return '사진은 50MB 이하로 촬영해 주세요.'
  }
  return null
}

export function getCompletionErrorMessage(error: unknown): string {
  const defaultMessage = '배송 완료 인증을 처리하지 못했습니다. 현재 상태를 확인한 뒤 다시 시도해 주세요.'

  if (!isAxiosError<ApiResponseRiderDeliveryCompleteResponse>(error)) {
    return defaultMessage
  }
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  }
  return error.response.data?.message || defaultMessage
}
