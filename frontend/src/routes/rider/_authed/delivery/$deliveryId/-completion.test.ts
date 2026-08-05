import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import {
  getCompletionErrorMessage,
  MAX_COMPLETION_PHOTO_BYTES,
  validateCompletionPhoto,
} from './-completion'

function httpError(status: number, message?: string): AxiosError<{ message?: string }> {
  const response: AxiosResponse<{ message?: string }> = {
    data: { message },
    status,
    statusText: 'Error',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
  }
  return new AxiosError('http error', String(status), response.config, undefined, response)
}

describe('배송 완료 인증 사진 검증', () => {
  it('이미지 파일을 허용한다', () => {
    expect(validateCompletionPhoto({ type: 'image/jpeg', size: 1024 })).toBeNull()
  })

  it('이미지가 아니거나 비어 있거나 50MB를 넘는 파일을 거부한다', () => {
    expect(validateCompletionPhoto({ type: 'application/pdf', size: 1024 })).toBe('사진 파일만 선택할 수 있습니다.')
    expect(validateCompletionPhoto({ type: 'image/jpeg', size: 0 })).toContain('비어 있는')
    expect(validateCompletionPhoto({ type: 'image/jpeg', size: MAX_COMPLETION_PHOTO_BYTES + 1 })).toContain('50MB')
  })
})

describe('배송 완료 인증 오류 문구', () => {
  it('서버 메시지를 표시한다', () => {
    expect(getCompletionErrorMessage(httpError(409, '이미 완료 처리된 배송입니다.'))).toBe('이미 완료 처리된 배송입니다.')
  })

  it('네트워크 오류와 알 수 없는 오류를 구분한다', () => {
    expect(getCompletionErrorMessage(new AxiosError('Network Error', AxiosError.ERR_NETWORK))).toContain('네트워크')
    expect(getCompletionErrorMessage(new Error('unknown'))).toContain('배송 완료 인증을 처리하지 못했습니다.')
  })
})
