import { useState, type FormEvent } from 'react'
import { createFileRoute, Link, useRouter } from '@tanstack/react-router'
import { useCustomerSignup } from '@/api/generated/customer-signup/customer-signup'
import { useCheckLoginIdAvailability } from '@/api/generated/login-id/login-id'
import {
  useConfirmPhoneVerification,
  useRequestPhoneVerification,
} from '@/api/generated/phone-verification/phone-verification'
import {
  PhoneVerificationConfirmRequestPurpose,
  PhoneVerificationRequestPurpose,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { PhoneVerificationToast } from '@/shared/auth/PhoneVerificationToast'
import { PasswordVisibilityInput } from '@/shared/auth/PasswordVisibilityInput'
import { SignupLogo } from '@/shared/auth/SignupLogo'
import {
  classifyCustomerSignupError,
  getApiErrorMessage,
  normalizePhoneNumber,
  shouldRestartPhoneVerification,
  validateCustomerSignup,
  validatePhoneNumber,
  type CustomerSignupFieldErrors,
  type CustomerSignupFields,
} from './-signupForm'

export const Route = createFileRoute('/customer/signup/')({
  component: CustomerSignup,
})

type LoginIdStatus = 'idle' | 'available' | 'unavailable'
type PhoneVerificationStatus = 'idle' | 'code-sent' | 'verified'
type DebugCodeToast = { code: string; id: number }

const initialFields: CustomerSignupFields = {
  loginId: '',
  password: '',
  name: '',
  phoneNumber: '',
  verificationCode: '',
}

function CustomerSignup() {
  const router = useRouter()
  const [fields, setFields] = useState<CustomerSignupFields>(initialFields)
  const [fieldErrors, setFieldErrors] = useState<CustomerSignupFieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [loginIdStatus, setLoginIdStatus] = useState<LoginIdStatus>('idle')
  const [checkedLoginId, setCheckedLoginId] = useState<string | null>(null)
  const [phoneStatus, setPhoneStatus] = useState<PhoneVerificationStatus>('idle')
  const [requestedPhoneNumber, setRequestedPhoneNumber] = useState<string | null>(null)
  const [verifiedPhoneNumber, setVerifiedPhoneNumber] = useState<string | null>(null)
  const [phoneVerificationToken, setPhoneVerificationToken] = useState<string | null>(null)
  const [debugCodeToast, setDebugCodeToast] = useState<DebugCodeToast | null>(null)

  const loginIdQuery = useCheckLoginIdAvailability(
    { loginId: fields.loginId.trim() },
    { query: { enabled: false, retry: false } },
  )

  const requestPhoneMutation = useRequestPhoneVerification({
    mutation: {
      onSuccess: (response, variables) => {
        if (!response.data) {
          setFieldErrors((current) => ({
            ...current,
            phoneNumber: '인증번호 발송 응답을 확인할 수 없습니다. 다시 시도해 주세요.',
          }))
          return
        }

        const phoneNumber = normalizePhoneNumber(variables.data.phoneNumber)
        setRequestedPhoneNumber(phoneNumber)
        setVerifiedPhoneNumber(null)
        setPhoneVerificationToken(null)
        setPhoneStatus('code-sent')
        const receivedDebugCode = response.data.debugCode ?? null
        setDebugCodeToast((current) => receivedDebugCode
          ? { code: receivedDebugCode, id: (current?.id ?? 0) + 1 }
          : null)
        setFields((current) => ({ ...current, verificationCode: receivedDebugCode ?? '' }))
        setFieldErrors((current) => ({
          ...current,
          phoneNumber: undefined,
          verificationCode: undefined,
        }))
      },
      onError: (error) => {
        setFieldErrors((current) => ({
          ...current,
          phoneNumber: getApiErrorMessage(error, '인증번호를 전송하지 못했습니다. 다시 시도해 주세요.'),
        }))
      },
    },
  })

  const confirmPhoneMutation = useConfirmPhoneVerification({
    mutation: {
      onSuccess: (response, variables) => {
        const token = response.data?.verificationToken
        if (!token) {
          setFieldErrors((current) => ({
            ...current,
            verificationCode: '인증 결과를 확인할 수 없습니다. 인증번호를 다시 요청해 주세요.',
          }))
          return
        }

        const phoneNumber = normalizePhoneNumber(variables.data.phoneNumber)
        setVerifiedPhoneNumber(phoneNumber)
        setPhoneVerificationToken(token)
        setPhoneStatus('verified')
        setFieldErrors((current) => ({
          ...current,
          phoneNumber: undefined,
          verificationCode: undefined,
        }))
      },
      onError: (error) => {
        if (shouldRestartPhoneVerification(error)) {
          resetPhoneVerification()
          setFieldErrors((current) => ({
            ...current,
            phoneNumber: getApiErrorMessage(error, '인증번호를 다시 요청해 주세요.'),
          }))
          return
        }

        setFieldErrors((current) => ({
          ...current,
          verificationCode: getApiErrorMessage(error, '인증번호를 확인하지 못했습니다. 다시 시도해 주세요.'),
        }))
      },
    },
  })

  const signupMutation = useCustomerSignup({
    mutation: {
      onSuccess: () => {
        // 완료된 회원가입 폼으로 되돌아가지 않도록 로그인 화면으로 교체한다.
        void router.navigate({ to: '/customer/login', replace: true })
      },
      onError: (error) => {
        const result = classifyCustomerSignupError(error)
        setFormError(null)

        if (result.target === 'loginId') {
          resetLoginIdCheck()
          setFieldErrors((current) => ({ ...current, loginId: result.message }))
          return
        }
        if (result.target === 'phoneNumber') {
          resetPhoneVerification()
          setFieldErrors((current) => ({ ...current, phoneNumber: result.message }))
          return
        }
        if (result.target === 'password') {
          setFieldErrors((current) => ({ ...current, password: result.message }))
          return
        }
        if (result.target === 'identity') {
          resetLoginIdCheck()
          resetPhoneVerification()
          setFieldErrors((current) => ({
            ...current,
            loginId: result.message,
            phoneNumber: result.message,
          }))
          return
        }
        setFormError(result.message)
      },
    },
  })

  function resetLoginIdCheck() {
    setCheckedLoginId(null)
    setLoginIdStatus('idle')
  }

  function resetPhoneVerification() {
    setPhoneStatus('idle')
    setRequestedPhoneNumber(null)
    setVerifiedPhoneNumber(null)
    setPhoneVerificationToken(null)
    setDebugCodeToast(null)
    setFields((current) => ({ ...current, verificationCode: '' }))
  }

  function updateField(field: keyof CustomerSignupFields, value: string) {
    const nextValue = field === 'phoneNumber'
      ? value.replace(/\D/g, '').slice(0, 11)
      : field === 'verificationCode'
        ? value.replace(/\D/g, '').slice(0, 6)
        : value

    setFields((current) => ({ ...current, [field]: nextValue }))
    setFieldErrors((current) => ({ ...current, [field]: undefined }))
    setFormError(null)

    if (field === 'loginId') {
      resetLoginIdCheck()
    }
    if (field === 'phoneNumber') {
      resetPhoneVerification()
    }
  }

  async function handleLoginIdCheck() {
    const loginId = fields.loginId.trim()
    if (!loginId) {
      setFieldErrors((current) => ({ ...current, loginId: '아이디를 입력해 주세요.' }))
      return
    }
    if (loginId.length > 50) {
      setFieldErrors((current) => ({ ...current, loginId: '아이디는 50자를 넘을 수 없습니다.' }))
      return
    }
    if (loginIdQuery.isFetching) {
      return
    }

    setFormError(null)
    const result = await loginIdQuery.refetch()
    if (result.error) {
      resetLoginIdCheck()
      setFieldErrors((current) => ({
        ...current,
        loginId: getApiErrorMessage(result.error, '아이디 중복 확인에 실패했습니다. 다시 시도해 주세요.'),
      }))
      return
    }

    if (result.data?.data?.available) {
      setCheckedLoginId(loginId)
      setLoginIdStatus('available')
      setFieldErrors((current) => ({ ...current, loginId: undefined }))
      return
    }

    setCheckedLoginId(null)
    setLoginIdStatus('unavailable')
    setFieldErrors((current) => ({
      ...current,
      loginId: result.data?.data?.reason || '이미 사용 중인 아이디입니다.',
    }))
  }

  function handleRequestPhoneVerification() {
    if (requestPhoneMutation.isPending || confirmPhoneMutation.isPending) {
      return
    }

    const phoneError = validatePhoneNumber(fields.phoneNumber)
    if (phoneError) {
      setFieldErrors((current) => ({ ...current, phoneNumber: phoneError }))
      return
    }

    setFormError(null)
    requestPhoneMutation.mutate({
      data: {
        phoneNumber: normalizePhoneNumber(fields.phoneNumber),
        purpose: PhoneVerificationRequestPurpose.SIGNUP,
      },
    })
  }

  function handleConfirmPhoneVerification() {
    if (confirmPhoneMutation.isPending || phoneStatus !== 'code-sent') {
      return
    }
    if (fields.verificationCode.length !== 6) {
      setFieldErrors((current) => ({
        ...current,
        verificationCode: '6자리 인증번호를 입력해 주세요.',
      }))
      return
    }

    const phoneNumber = normalizePhoneNumber(fields.phoneNumber)
    if (requestedPhoneNumber !== phoneNumber) {
      resetPhoneVerification()
      setFieldErrors((current) => ({
        ...current,
        phoneNumber: '휴대전화 번호가 변경되었습니다. 인증번호를 다시 요청해 주세요.',
      }))
      return
    }

    confirmPhoneMutation.mutate({
      data: {
        code: fields.verificationCode,
        phoneNumber,
        purpose: PhoneVerificationConfirmRequestPurpose.SIGNUP,
      },
    })
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (signupMutation.isPending) {
      return
    }

    const errors = validateCustomerSignup(fields, {
      checkedLoginId,
      phoneVerificationToken,
      verifiedPhoneNumber,
    })
    setFieldErrors(errors)
    setFormError(null)

    if (Object.keys(errors).length > 0 || !phoneVerificationToken) {
      return
    }

    signupMutation.mutate({
      data: {
        loginId: fields.loginId.trim(),
        password: fields.password,
        passwordConfirm: fields.password,
        name: fields.name.trim(),
        phoneNumber: normalizePhoneNumber(fields.phoneNumber),
        phoneVerificationToken,
        // #72 약관 목록 조회 API가 완료되면 실제 동의한 termId 목록으로 교체한다.
        agreedTermIds: [],
      },
    })
  }

  const phoneBusy = requestPhoneMutation.isPending || confirmPhoneMutation.isPending

  return (
    <div className="max-w-md mx-auto min-h-screen bg-surface-container-lowest relative flex flex-col shadow-sm">
      {debugCodeToast && (
        <PhoneVerificationToast
          key={debugCodeToast.id}
          code={debugCodeToast.code}
          onClose={() => setDebugCodeToast(null)}
        />
      )}
      <header className="bg-surface text-on-surface docked full-width top-0 border-b border-surface-container flat no shadows flex justify-between items-center w-full px-container-margin h-14 sticky z-20">
        <Link to="/customer/login" className="text-on-surface-variant hover:bg-surface-container-low rounded-full w-10 h-10 flex items-center justify-center transition-colors" aria-label="고객 로그인으로 돌아가기">
          <span className="material-symbols-outlined">arrow_back</span>
        </Link>
        <h1 className="flex-1 text-center text-headline-sm font-bold text-on-surface">회원가입</h1>
        <div className="h-10 w-10" aria-hidden="true" />
      </header>

      <form className="flex-1 flex flex-col" onSubmit={handleSubmit} noValidate>
        <main aria-label="고객 회원가입" className="flex flex-1 flex-col justify-center gap-xl px-container-margin pb-32 pt-lg">
          <div className="flex justify-center" aria-hidden="true">
            <SignupLogo />
          </div>

          <section className="flex flex-col gap-lg">
            <h2 className="text-headline-md font-headline-md text-on-surface">로그인 정보</h2>

            <div className="flex flex-col gap-2">
              <label className="text-label-lg font-label-lg text-on-surface-variant" htmlFor="userId">아이디</label>
              <div className="flex gap-2">
                <input
                  className="min-w-0 flex-1 h-[52px] px-4 bg-surface-container-lowest border border-surface-container rounded-xl text-body-lg font-body-lg text-on-surface outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-colors placeholder:text-surface-dim"
                  id="userId"
                  name="loginId"
                  autoComplete="username"
                  value={fields.loginId}
                  onChange={(event) => updateField('loginId', event.target.value)}
                  aria-invalid={Boolean(fieldErrors.loginId)}
                  aria-describedby={fieldErrors.loginId ? 'signup-login-id-error' : loginIdStatus === 'available' ? 'signup-login-id-status' : undefined}
                  placeholder="아이디 입력"
                  type="text"
                />
                <button
                  className="h-[52px] px-4 bg-surface-container border border-surface-container text-on-surface font-label-lg text-label-lg rounded-xl hover:bg-surface-variant transition-colors whitespace-nowrap disabled:cursor-not-allowed disabled:opacity-60"
                  type="button"
                  disabled={loginIdQuery.isFetching || signupMutation.isPending}
                  onClick={() => void handleLoginIdCheck()}
                >
                  {loginIdQuery.isFetching ? '확인 중...' : '중복 확인'}
                </button>
              </div>
              {fieldErrors.loginId && <FieldError id="signup-login-id-error">{fieldErrors.loginId}</FieldError>}
              {loginIdStatus === 'available' && (
                <p id="signup-login-id-status" className="px-1 text-body-md text-tertiary" aria-live="polite">
                  사용할 수 있는 아이디입니다.
                </p>
              )}
            </div>

            <div className="flex flex-col gap-2">
              <label className="text-label-lg font-label-lg text-on-surface-variant" htmlFor="password">비밀번호</label>
              <PasswordVisibilityInput
                className="w-full h-[52px] px-4 bg-surface-container-lowest border border-surface-container rounded-xl text-body-lg font-body-lg text-on-surface outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-colors placeholder:text-surface-dim"
                value={fields.password}
                onChange={(event) => updateField('password', event.target.value)}
                invalid={Boolean(fieldErrors.password)}
                describedBy={fieldErrors.password ? 'signup-password-error' : undefined}
              />
              {fieldErrors.password && <FieldError id="signup-password-error">{fieldErrors.password}</FieldError>}
            </div>
          </section>

          <div className="w-full h-px bg-surface-container" />

          <section className="flex flex-col gap-lg">
            <h2 className="text-headline-md font-headline-md text-on-surface">개인 정보</h2>

            <div className="flex flex-col gap-2">
              <label className="text-label-lg font-label-lg text-on-surface-variant" htmlFor="userName">이름</label>
              <input
                className="w-full h-[52px] px-4 bg-surface-container-lowest border border-surface-container rounded-xl text-body-lg font-body-lg text-on-surface outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-colors placeholder:text-surface-dim"
                id="userName"
                name="name"
                autoComplete="name"
                value={fields.name}
                onChange={(event) => updateField('name', event.target.value)}
                aria-invalid={Boolean(fieldErrors.name)}
                aria-describedby={fieldErrors.name ? 'signup-name-error' : undefined}
                placeholder="이름 입력"
                type="text"
              />
              {fieldErrors.name && <FieldError id="signup-name-error">{fieldErrors.name}</FieldError>}
            </div>

            <div className="flex flex-col gap-2">
              <label className="text-label-lg font-label-lg text-on-surface-variant" htmlFor="userPhone">휴대전화 번호</label>
              <div className="flex gap-2">
                <input
                  className="min-w-0 flex-1 h-[52px] px-4 bg-surface-container-lowest border border-surface-container rounded-xl text-body-lg font-body-lg text-on-surface outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-colors placeholder:text-surface-dim disabled:bg-surface-container-low disabled:text-on-surface-variant"
                  id="userPhone"
                  name="phoneNumber"
                  autoComplete="tel"
                  inputMode="numeric"
                  value={fields.phoneNumber}
                  onChange={(event) => updateField('phoneNumber', event.target.value)}
                  aria-invalid={Boolean(fieldErrors.phoneNumber)}
                  aria-describedby={fieldErrors.phoneNumber ? 'signup-phone-error' : phoneStatus === 'verified' ? 'signup-phone-status' : undefined}
                  placeholder="'-' 없이 숫자만 입력"
                  type="tel"
                  disabled={phoneBusy || signupMutation.isPending || phoneStatus === 'verified'}
                />
                <button
                  className="h-[52px] px-4 bg-surface-container border border-surface-container text-on-surface font-label-lg text-label-lg rounded-xl hover:bg-surface-variant transition-colors whitespace-nowrap disabled:cursor-not-allowed disabled:opacity-60"
                  type="button"
                  disabled={phoneBusy || signupMutation.isPending || phoneStatus === 'verified'}
                  onClick={handleRequestPhoneVerification}
                >
                  {requestPhoneMutation.isPending
                    ? '전송 중...'
                    : phoneStatus === 'code-sent'
                      ? '재전송'
                      : phoneStatus === 'verified'
                        ? '인증 완료'
                        : '인증번호 전송'}
                </button>
              </div>
              {fieldErrors.phoneNumber && <FieldError id="signup-phone-error">{fieldErrors.phoneNumber}</FieldError>}
              {phoneStatus === 'verified' && (
                <div className="flex items-center justify-between gap-3">
                  <p id="signup-phone-status" className="px-1 text-body-md text-tertiary" aria-live="polite">
                    휴대전화 인증이 완료되었습니다.
                  </p>
                  <button
                    type="button"
                    className="text-label-md text-on-surface-variant underline"
                    onClick={resetPhoneVerification}
                  >
                    번호 변경
                  </button>
                </div>
              )}

              {phoneStatus === 'code-sent' && (
                <div className="flex flex-col gap-2 pt-1">
                  <div className="flex gap-2">
                    <input
                      className="min-w-0 flex-1 h-[52px] px-4 bg-surface-container-lowest border border-surface-container rounded-xl text-body-lg font-body-lg text-on-surface outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-colors placeholder:text-surface-dim"
                      id="verificationCode"
                      name="verificationCode"
                      autoComplete="one-time-code"
                      inputMode="numeric"
                      value={fields.verificationCode}
                      onChange={(event) => updateField('verificationCode', event.target.value)}
                      aria-invalid={Boolean(fieldErrors.verificationCode)}
                      aria-describedby={fieldErrors.verificationCode ? 'signup-verification-code-error' : undefined}
                      placeholder="6자리 인증번호"
                      type="text"
                    />
                    <button
                      className="h-[52px] px-4 bg-primary-container text-on-primary-container font-label-lg text-label-lg rounded-xl whitespace-nowrap disabled:cursor-not-allowed disabled:opacity-60"
                      type="button"
                      disabled={confirmPhoneMutation.isPending || requestPhoneMutation.isPending}
                      onClick={handleConfirmPhoneVerification}
                    >
                      {confirmPhoneMutation.isPending ? '확인 중...' : '인증 확인'}
                    </button>
                  </div>
                  {fieldErrors.verificationCode && <FieldError id="signup-verification-code-error">{fieldErrors.verificationCode}</FieldError>}
                </div>
              )}
            </div>
          </section>

          {formError && (
            <p className="rounded-xl bg-error-container px-4 py-3 text-body-md text-on-error-container" role="alert">
              {formError}
            </p>
          )}
        </main>

        <div className="fixed bottom-0 left-1/2 w-full max-w-md -translate-x-1/2 p-container-margin pb-8 bg-surface-container-lowest border-t border-surface-container z-20">
          <button
            className="w-full h-14 bg-primary-container text-[#191919] font-headline-md text-headline-sm rounded-xl flex items-center justify-center active:scale-[0.98] transition-transform shadow-sm disabled:cursor-not-allowed disabled:opacity-60"
            type="submit"
            disabled={signupMutation.isPending}
            aria-busy={signupMutation.isPending}
          >
            {signupMutation.isPending ? '가입 중...' : '가입하기'}
          </button>
        </div>
      </form>
    </div>
  )
}

function FieldError({ id, children }: { id: string; children: string }) {
  return (
    <p id={id} className="px-1 text-body-md text-error" role="alert">
      {children}
    </p>
  )
}
