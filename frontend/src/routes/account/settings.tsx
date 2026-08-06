import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import { useCustomerLogout } from '@/api/generated/customer-logout/customer-logout'
import { useRiderLogout } from '@/api/generated/rider-logout/rider-logout'
import { clearStoredSessionRole } from '@/shared/auth/sessionRole'
import { resolveLogoutFailure, type LogoutNotice } from './-accountSettings'

/**
 * 계정 설정 화면(#222). 지금은 로그아웃만 연결한다 —
 * 비밀번호 재설정(#23)·회원 탈퇴(#95)는 백엔드가 아직 없어 항목 자체를 두지 않았다(사람 확인).
 *
 * 역할 무관 화면이라 `account/route.tsx` 가드가 로그인만 확인하고, 여기서 세션의 역할을 보고
 * 고객·라이더 로그아웃 API 중 하나만 호출한다. 두 API 를 다 부르면 반대 역할 응답이 공용
 * SESSION_ID 쿠키를 건드린다(#288 과 같은 종류의 문제).
 */
export const Route = createFileRoute('/account/settings')({
  component: AccountSettings,
})

function AccountSettings() {
  const router = useRouter()
  const queryClient = useQueryClient()
  const { session } = Route.useRouteContext()
  const [confirming, setConfirming] = useState(false)
  const [failure, setFailure] = useState<LogoutNotice | null>(null)

  const customerLogout = useCustomerLogout()
  const riderLogout = useRiderLogout()
  const isRider = session.role === 'RIDER'
  const pending = customerLogout.isPending || riderLogout.isPending

  const memberName = session.role === 'CUSTOMER' ? session.customer.name : isRider ? session.rider.name : undefined
  const loginId = session.role === 'CUSTOMER' ? session.customer.loginId : isRider ? session.rider.loginId : undefined

  /**
   * 이 기기에 남은 로그인 흔적을 지우고 랜딩으로 보낸다.
   *
   * 쿼리 캐시를 통째로 비우는 이유는 세션 조회 결과뿐 아니라 배송·포인트처럼 개인 데이터를 담은
   * 캐시가 남아 다음 사용자에게 잠깐 보이는 것을 막기 위해서다(#222). 역할 힌트도 같이 지워야
   * 랜딩 가드가 세션 API 를 다시 호출하지 않고 비로그인으로 판정한다.
   */
  async function leaveAfterLogout() {
    queryClient.clear()
    clearStoredSessionRole()
    // replace 로 이동해 뒤로 가기가 로그아웃된 설정 화면으로 돌아가지 않게 한다.
    await router.navigate({ to: '/', replace: true })
  }

  function requestLogout() {
    if (pending) {
      return
    }

    setFailure(null)
    const handlers = {
      onSuccess: () => {
        setConfirming(false)
        void leaveAfterLogout()
      },
      onError: (error: unknown) => {
        const outcome = resolveLogoutFailure(error)
        setConfirming(false)

        if (outcome.kind === 'already-signed-out') {
          void leaveAfterLogout()
          return
        }

        setFailure(outcome)
        if (outcome.kind === 'local-only') {
          // 서버 세션이 지워졌는지 알 수 없다. 이 기기의 캐시는 지금 비우고, 이동은 안내를 읽은
          // 사용자에게 맡긴다 — 곧바로 이동시키면 실패 사실을 알릴 자리가 없다.
          queryClient.clear()
          clearStoredSessionRole()
        }
      },
    }

    if (isRider) {
      riderLogout.mutate(undefined, handlers)
    } else {
      customerLogout.mutate(undefined, handlers)
    }
  }

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-md flex-col bg-surface text-on-surface">
      <header className="sticky top-0 z-20 flex h-16 items-center border-b border-outline-variant bg-surface-container-lowest px-4">
        <button
          type="button"
          aria-label="이전 화면으로 돌아가기"
          onClick={() => router.history.back()}
          className="flex h-11 w-11 items-center justify-center rounded-full text-on-surface transition-colors hover:bg-surface-container-low"
        >
          <span className="material-symbols-outlined" aria-hidden="true">arrow_back</span>
        </button>
        <h1 className="flex-1 text-center text-headline-md font-bold">설정</h1>
        <span className="h-11 w-11" aria-hidden="true" />
      </header>

      <main aria-label="계정 설정" className="flex flex-1 flex-col gap-6 px-5 py-6">
        <section aria-label="로그인 계정" className="rounded-2xl bg-surface-container-low px-5 py-4">
          <p className="text-label-md text-secondary">{isRider ? '라이더' : '고객'}으로 로그인 중</p>
          <p className="mt-1 text-title-md font-bold">{memberName ?? '이름 없음'}</p>
          {loginId && <p className="mt-0.5 text-body-sm text-secondary">{loginId}</p>}
        </section>

        {failure && (
          <section
            role="alert"
            className="flex flex-col gap-3 rounded-2xl bg-error-container px-5 py-4 text-on-error-container"
          >
            <p className="text-body-md font-semibold">{failure.message}</p>
            {failure.kind === 'blocked' && isRider && (
              <button
                type="button"
                onClick={() => void router.navigate({ to: '/rider/delivery' })}
                className="min-h-12 rounded-xl bg-on-error-container px-4 text-label-lg font-bold text-on-error"
              >
                진행 중인 배송 보기
              </button>
            )}
            {failure.kind === 'local-only' && (
              <div className="flex gap-3">
                <button
                  type="button"
                  onClick={requestLogout}
                  disabled={pending}
                  className="min-h-12 flex-1 rounded-xl border border-on-error-container px-4 text-label-lg font-bold disabled:opacity-50"
                >
                  다시 시도
                </button>
                <button
                  type="button"
                  onClick={() => void router.navigate({ to: '/', replace: true })}
                  className="min-h-12 flex-1 rounded-xl bg-on-error-container px-4 text-label-lg font-bold text-on-error"
                >
                  처음 화면으로
                </button>
              </div>
            )}
          </section>
        )}

        <section aria-label="계정 관리" className="flex flex-col">
          <button
            type="button"
            onClick={() => setConfirming(true)}
            disabled={pending}
            aria-busy={pending}
            className="flex min-h-14 items-center justify-between rounded-2xl px-5 text-left text-title-md font-semibold text-error transition-colors hover:bg-surface-container-low disabled:opacity-50"
          >
            <span>{pending ? '로그아웃 중…' : '로그아웃'}</span>
            <span className="material-symbols-outlined" aria-hidden="true">logout</span>
          </button>
        </section>
      </main>

      {confirming && (
        <LogoutConfirmDialog
          pending={pending}
          isRider={isRider}
          onClose={() => setConfirming(false)}
          onConfirm={requestLogout}
        />
      )}
    </div>
  )
}

function LogoutConfirmDialog({
  pending,
  isRider,
  onClose,
  onConfirm,
}: {
  pending: boolean
  isRider: boolean
  onClose: () => void
  onConfirm: () => void
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-4 sm:items-center">
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="logout-title"
        className="w-full max-w-sm rounded-2xl bg-surface-container-lowest p-6 shadow-xl"
      >
        <h2 id="logout-title" className="text-headline-md font-bold">로그아웃할까요?</h2>
        <p className="mt-2 text-body-md text-secondary">
          {isRider
            ? '운행 상태는 종료로 바뀌고 새 콜을 받지 않습니다. 배송을 수행 중이면 로그아웃할 수 없습니다.'
            : '다시 이용하려면 로그인해야 합니다.'}
        </p>
        <div className="mt-6 flex gap-3">
          <button
            type="button"
            onClick={onClose}
            disabled={pending}
            className="min-h-12 flex-1 rounded-xl border border-outline-variant text-label-lg font-bold disabled:opacity-50"
          >
            돌아가기
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={pending}
            className="min-h-12 flex-1 rounded-xl bg-error text-label-lg font-bold text-on-error disabled:opacity-50"
          >
            {pending ? '로그아웃 중…' : '로그아웃'}
          </button>
        </div>
      </section>
    </div>
  )
}
