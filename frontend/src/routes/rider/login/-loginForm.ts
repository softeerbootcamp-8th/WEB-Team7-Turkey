import { isAxiosError } from 'axios'

export interface RiderLoginFields {
  loginId: string
  password: string
}

export type RiderLoginFieldErrors = Partial<Record<keyof RiderLoginFields, string>>

export function validateRiderLogin(fields: RiderLoginFields): RiderLoginFieldErrors {
  const errors: RiderLoginFieldErrors = {}

  if (!fields.loginId.trim()) {
    errors.loginId = '아이디를 입력해 주세요.'
  }
  if (!fields.password) {
    errors.password = '비밀번호를 입력해 주세요.'
  }

  return errors
}

export function getRiderLoginErrorMessage(error: unknown): string {
  if (isAxiosError(error)) {
    if (error.response?.status === 401) {
      return '아이디 또는 비밀번호가 일치하지 않습니다.'
    }
    if (!error.response) {
      return '네트워크 연결을 확인하고 다시 시도해 주세요.'
    }
  }

  return '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'
}
