import { isAxiosError } from 'axios'

export interface RiderSignupFields {
  loginId: string
  password: string
  name: string
  phoneNumber: string
  verificationCode: string
}

export type RiderSignupFieldErrors = Partial<Record<keyof RiderSignupFields, string>>

export interface RiderSignupValidationContext {
  checkedLoginId: string | null
  phoneVerificationToken: string | null
  verifiedPhoneNumber: string | null
}

export interface RiderSignupRequestError {
  target: 'loginId' | 'phoneNumber' | 'password' | 'identity' | 'form'
  message: string
}

const PHONE_NUMBER_PATTERN = /^01(?:0|1|[6-9])\d{3,4}\d{4}$/

export function normalizePhoneNumber(phoneNumber: string): string {
  return phoneNumber.replace(/\D/g, '')
}

export function validatePhoneNumber(phoneNumber: string): string | undefined {
  const normalized = normalizePhoneNumber(phoneNumber)
  if (!normalized) {
    return '휴대전화 번호를 입력해 주세요.'
  }
  if (!PHONE_NUMBER_PATTERN.test(normalized)) {
    return '휴대전화 번호 형식이 올바르지 않습니다.'
  }
  return undefined
}

export function validateRiderSignup(
  fields: RiderSignupFields,
  context: RiderSignupValidationContext,
): RiderSignupFieldErrors {
  const errors: RiderSignupFieldErrors = {}
  const loginId = fields.loginId.trim()
  const name = fields.name.trim()
  const phoneNumber = normalizePhoneNumber(fields.phoneNumber)

  if (!loginId) {
    errors.loginId = '아이디를 입력해 주세요.'
  } else if (loginId.length > 50) {
    errors.loginId = '아이디는 50자를 넘을 수 없습니다.'
  } else if (context.checkedLoginId !== loginId) {
    errors.loginId = '아이디 중복 확인을 해 주세요.'
  }

  if (!fields.password) {
    errors.password = '비밀번호를 입력해 주세요.'
  }

  if (!name) {
    errors.name = '이름을 입력해 주세요.'
  } else if (name.length > 50) {
    errors.name = '이름은 50자를 넘을 수 없습니다.'
  }

  const phoneError = validatePhoneNumber(phoneNumber)
  if (phoneError) {
    errors.phoneNumber = phoneError
  } else if (
    !context.phoneVerificationToken
    || normalizePhoneNumber(context.verifiedPhoneNumber ?? '') !== phoneNumber
  ) {
    errors.phoneNumber = '휴대전화 인증을 완료해 주세요.'
  }

  return errors
}

interface ApiErrorBody {
  message?: string
}

export function getApiErrorMessage(error: unknown, fallback: string): string {
  if (!isAxiosError<ApiErrorBody>(error)) {
    return fallback
  }
  if (!error.response) {
    return '네트워크 연결을 확인하고 다시 시도해 주세요.'
  }
  return error.response.data?.message || fallback
}

export function classifyRiderSignupError(error: unknown): RiderSignupRequestError {
  const fallback = '회원가입 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'
  if (!isAxiosError<ApiErrorBody>(error)) {
    return { target: 'form', message: fallback }
  }
  if (!error.response) {
    return { target: 'form', message: '네트워크 연결을 확인하고 다시 시도해 주세요.' }
  }

  const message = error.response.data?.message || fallback
  if (error.response.status === 409) {
    const hasLoginId = message.includes('아이디')
    const hasPhoneNumber = message.includes('휴대전화')
    if (hasLoginId && hasPhoneNumber) {
      return { target: 'identity', message }
    }
    if (hasLoginId) {
      return { target: 'loginId', message }
    }
    if (hasPhoneNumber) {
      return { target: 'phoneNumber', message }
    }
  }
  if (error.response.status === 400 && message.includes('인증')) {
    return { target: 'phoneNumber', message }
  }
  if (error.response.status === 400 && message.includes('비밀번호')) {
    return { target: 'password', message }
  }
  return { target: 'form', message }
}

export function shouldRestartPhoneVerification(error: unknown): boolean {
  return isAxiosError(error) && (error.response?.status === 404 || error.response?.status === 429)
}
