import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import {
  classifyRiderSignupError,
  normalizePhoneNumber,
  shouldRestartPhoneVerification,
  validatePhoneNumber,
  validateRiderSignup,
  type RiderSignupFields,
} from './-signupForm'

const validFields: RiderSignupFields = {
  loginId: 'rider1',
  password: 'password',
  name: '홍길동',
  phoneNumber: '01012345678',
  verificationCode: '123456',
}

function httpError(status: number, message?: string): AxiosError<{ message?: string }> {
  const response = {
    status,
    data: { message },
    statusText: '',
    headers: {},
    config: {},
  } as unknown as AxiosResponse<{ message?: string }>
  return new AxiosError('http error', String(status), { headers: new AxiosHeaders() }, undefined, response)
}

describe('validateRiderSignup', () => {
  it('중복 확인과 휴대전화 인증을 완료하고 필수값이 유효하면 오류가 없다', () => {
    expect(validateRiderSignup(validFields, {
      checkedLoginId: 'rider1',
      phoneVerificationToken: 'verified-token',
      verifiedPhoneNumber: '010-1234-5678',
    })).toEqual({})
  })

  it('아이디 중복 확인과 휴대전화 인증이 없으면 제출을 막는다', () => {
    expect(validateRiderSignup(validFields, {
      checkedLoginId: null,
      phoneVerificationToken: null,
      verifiedPhoneNumber: null,
    })).toMatchObject({
      loginId: '아이디 중복 확인을 해 주세요.',
      phoneNumber: '휴대전화 인증을 완료해 주세요.',
    })
  })

  it('필수 입력값 누락을 필드별로 반환한다', () => {
    expect(validateRiderSignup({
      ...validFields,
      loginId: ' ',
      password: '',
      name: '',
    }, {
      checkedLoginId: null,
      phoneVerificationToken: null,
      verifiedPhoneNumber: null,
    })).toMatchObject({
      loginId: '아이디를 입력해 주세요.',
      password: '비밀번호를 입력해 주세요.',
      name: '이름을 입력해 주세요.',
    })
  })
})

describe('휴대전화 번호 검증', () => {
  it('하이픈을 제거해 같은 번호로 비교할 수 있다', () => {
    expect(normalizePhoneNumber('010-1234-5678')).toBe('01012345678')
  })

  it('지원하지 않는 형식을 거부한다', () => {
    expect(validatePhoneNumber('0212345678')).toBe('휴대전화 번호 형식이 올바르지 않습니다.')
  })
})

describe('서버 오류 분류', () => {
  it('네트워크 오류를 가입 실패 응답과 구분한다', () => {
    expect(classifyRiderSignupError(new AxiosError('Network Error'))).toEqual({
      target: 'form',
      message: '네트워크 연결을 확인하고 다시 시도해 주세요.',
    })
  })

  it('동시 가입으로 발생한 아이디 중복을 해당 필드 오류로 분류한다', () => {
    expect(classifyRiderSignupError(httpError(409, '이미 사용 중인 아이디입니다.'))).toEqual({
      target: 'loginId',
      message: '이미 사용 중인 아이디입니다.',
    })
  })

  it('인증 토큰 만료를 휴대전화 필드 오류로 분류한다', () => {
    expect(classifyRiderSignupError(httpError(400, '휴대전화 인증이 필요하거나 인증이 만료되었습니다.')))
      .toEqual({
        target: 'phoneNumber',
        message: '휴대전화 인증이 필요하거나 인증이 만료되었습니다.',
      })
  })

  it('인증번호 이력 없음과 시도 초과는 인증 재시작 대상으로 본다', () => {
    expect(shouldRestartPhoneVerification(httpError(404))).toBe(true)
    expect(shouldRestartPhoneVerification(httpError(429))).toBe(true)
    expect(shouldRestartPhoneVerification(httpError(400))).toBe(false)
  })
})
