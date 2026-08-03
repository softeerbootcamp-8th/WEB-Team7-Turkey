import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, Link, redirect, useRouter } from '@tanstack/react-router'
import { useRiderLogin } from '@/api/generated/rider-login/rider-login'
import { resolveGuestGuard, type SessionInfo } from '@/shared/auth/guard'
import { validateRedirectSearch } from '@/shared/auth/redirectSearch'
import { cacheAuthenticatedRider, ensureSessionInfo } from '@/shared/auth/session'
import {
  getRiderLoginErrorMessage,
  validateRiderLogin,
  type RiderLoginFieldErrors,
  type RiderLoginFields,
} from './-loginForm'

export const Route = createFileRoute('/rider/login/')({
  // 가드가 보존한 원래 목적지(#195). 로그인 성공 후 이 경로로 돌려보낸다.
  validateSearch: validateRedirectSearch,
  beforeLoad: async ({ context }) => {
    let session: SessionInfo
    try {
      session = await ensureSessionInfo(context.queryClient)
    } catch {
      return
    }

    const decision = resolveGuestGuard(session)
    if (decision.action === 'redirect') {
      throw redirect({ to: decision.to })
    }
  },
  component: RiderLogin,
})

function RiderLogin() {
  const router = useRouter()
  const queryClient = useQueryClient()
  const search = Route.useSearch()
  const [fields, setFields] = useState<RiderLoginFields>({ loginId: '', password: '' })
  const [fieldErrors, setFieldErrors] = useState<RiderLoginFieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [passwordVisible, setPasswordVisible] = useState(false)

  const loginMutation = useRiderLogin({
    mutation: {
      onSuccess: (response) => {
        if (!response.data) {
          setFormError('로그인 응답을 확인할 수 없습니다. 다시 시도해 주세요.')
          return
        }

        cacheAuthenticatedRider(queryClient, response.data)

        if (search.redirect) {
          router.history.push(search.redirect)
          return
        }

        void router.navigate({ to: '/rider' })
      },
      onError: (error) => {
        setFormError(getRiderLoginErrorMessage(error))
      },
    },
  })

  function updateField(field: keyof RiderLoginFields, value: string) {
    setFields((current) => ({ ...current, [field]: value }))
    setFieldErrors((current) => ({ ...current, [field]: undefined }))
    setFormError(null)
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (loginMutation.isPending) {
      return
    }

    const errors = validateRiderLogin(fields)
    setFieldErrors(errors)
    setFormError(null)

    if (Object.keys(errors).length > 0) {
      return
    }

    loginMutation.mutate({
      data: {
        loginId: fields.loginId.trim(),
        password: fields.password,
      },
    })
  }

  return (
    <div className="max-w-md mx-auto min-h-screen bg-surface-container-lowest relative flex flex-col shadow-sm">
      {/* TopAppBar */}
      <header className="bg-surface text-on-surface docked full-width top-0 border-b border-surface-container flat no shadows hover:bg-surface-container-low flex justify-between items-center w-full px-container-margin h-14 sticky z-10">
        <div className="flex items-center gap-xs">
          <Link to="/" className="p-2 -ml-2 rounded-full hover:bg-surface-variant transition-colors" aria-label="홈으로 돌아가기">
            <span className="material-symbols-outlined text-[24px]">arrow_back</span>
          </Link>
          <h1 className="text-headline-sm-mobile font-headline-sm-mobile text-on-surface font-bold">라이더 로그인</h1>
        </div>
        <Link to="/rider/signup" className="text-label-lg-mobile font-label-lg-mobile text-on-surface-variant hover:text-on-surface transition-colors px-2 py-1">
          회원가입
        </Link>
      </header>

      {/* Main Content Canvas */}
      <main aria-label="라이더 로그인" className="flex-grow flex flex-col px-container-margin pt-xl pb-safe gap-lg">

        {/* Welcome Area */}
        <div className="flex flex-col items-center justify-center pt-lg pb-md gap-sm">
          <div className="w-16 h-16 bg-primary-container text-on-primary-container rounded-2xl flex items-center justify-center mb-2">
            <span className="material-symbols-outlined text-[32px] font-[500]" style={{ fontVariationSettings: "'FILL' 1" }}>two_wheeler</span>
          </div>
          <h2 className="text-headline-lg-mobile font-headline-lg-mobile font-bold text-center">라이더님, 환영합니다!</h2>
          <p className="text-body-md font-body-md text-on-surface-variant text-center">지금 배차를 받고 수익을 시작해보세요.</p>
        </div>

        {/* Login Form */}
        <form className="flex flex-col gap-md w-full max-w-md mx-auto" onSubmit={handleSubmit} noValidate>

          {/* ID Input */}
          <div className="flex flex-col gap-xs">
            <label htmlFor="userId" className="text-label-md font-label-md text-on-surface-variant px-1">아이디</label>
            <div className="relative">
              <input
                type="text"
                id="userId"
                name="loginId"
                autoComplete="username"
                value={fields.loginId}
                onChange={(event) => updateField('loginId', event.target.value)}
                aria-invalid={Boolean(fieldErrors.loginId)}
                aria-describedby={fieldErrors.loginId ? 'rider-loginId-error' : undefined}
                placeholder="아이디를 입력해주세요"
                className="w-full h-[52px] bg-surface-container-lowest border border-surface-container rounded-xl px-4 text-body-lg font-body-lg focus:outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-all"
              />
            </div>
            {fieldErrors.loginId && (
              <p id="rider-loginId-error" className="px-1 text-body-md text-error" role="alert">
                {fieldErrors.loginId}
              </p>
            )}
          </div>

          {/* Password Input */}
          <div className="flex flex-col gap-xs">
            <label htmlFor="password" className="text-label-md font-label-md text-on-surface-variant px-1">비밀번호</label>
            <div className="relative">
              <input
                type={passwordVisible ? 'text' : 'password'}
                id="password"
                name="password"
                autoComplete="current-password"
                value={fields.password}
                onChange={(event) => updateField('password', event.target.value)}
                aria-invalid={Boolean(fieldErrors.password)}
                aria-describedby={fieldErrors.password ? 'rider-password-error' : undefined}
                placeholder="비밀번호를 입력해주세요"
                className="w-full h-[52px] bg-surface-container-lowest border border-surface-container rounded-xl px-4 pr-12 text-body-lg font-body-lg focus:outline-none focus:border-primary-container focus:ring-1 focus:ring-primary-container transition-all"
              />
              <button
                type="button"
                className="absolute right-4 top-1/2 transform -translate-y-1/2 text-on-surface-variant hover:text-on-surface"
                aria-label={passwordVisible ? '비밀번호 숨기기' : '비밀번호 표시'}
                aria-pressed={passwordVisible}
                onClick={() => setPasswordVisible((visible) => !visible)}
              >
                <span className="material-symbols-outlined text-[20px]">
                  {passwordVisible ? 'visibility' : 'visibility_off'}
                </span>
              </button>
            </div>
            {fieldErrors.password && (
              <p id="rider-password-error" className="px-1 text-body-md text-error" role="alert">
                {fieldErrors.password}
              </p>
            )}
          </div>

          {/* Options Row */}
          <div className="flex items-center justify-between px-1 pt-xs">
            <div className="flex items-center gap-sm">
              <a className="text-body-md font-body-md text-tertiary hover:underline" href="#">아이디 찾기</a>
              <span className="w-[1px] h-3 bg-surface-container-high"></span>
              <a className="text-body-md font-body-md text-tertiary hover:underline" href="#">비밀번호 찾기</a>
            </div>
          </div>

          {formError && (
            <p className="rounded-xl bg-error-container px-4 py-3 text-body-md text-on-error-container" role="alert">
              {formError}
            </p>
          )}

          {/* Primary Action */}
          <button
            type="submit"
            disabled={loginMutation.isPending}
            aria-busy={loginMutation.isPending}
            className="w-full h-[52px] bg-primary-container text-[#191919] text-headline-sm-mobile font-headline-sm-mobile font-bold rounded-xl mt-sm flex items-center justify-center active:scale-[0.98] transition-transform disabled:cursor-not-allowed disabled:opacity-60"
          >
            {loginMutation.isPending ? '로그인 중...' : '로그인'}
          </button>
        </form>
      </main>
    </div>
  )
}
