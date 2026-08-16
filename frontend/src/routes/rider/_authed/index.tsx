import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import {
  getGetRiderOperatingStatusQueryKey,
  useChangeRiderOperatingStatus,
  useGetRiderOperatingStatus,
} from '@/api/generated/rider-operating-status/rider-operating-status'
import { getGetRiderSessionQueryKey } from '@/api/generated/rider-session/rider-session'
import { getOperatingStatusErrorMessage, getOperatingStatusQueryErrorMessage } from './-riderHome'

export const Route = createFileRoute('/rider/_authed/')({
  component: RiderHome,
})

function RiderHome() {
  const router = useRouter()
  const queryClient = useQueryClient()
  const [statusError, setStatusError] = useState<string | null>(null)
  const operatingStatusQuery = useGetRiderOperatingStatus({ query: { retry: false } })
  const operatingStatusMutation = useChangeRiderOperatingStatus()
  const operatingStatus = operatingStatusQuery.data?.data?.operatingStatus
  const isAvailable = operatingStatus === 'AVAILABLE'

  useEffect(() => {
    if (operatingStatus === 'BUSY') {
      void router.navigate({ to: '/rider/delivery', replace: true })
    }
  }, [operatingStatus, router])

  function toggleOperating() {
    if (!operatingStatus || operatingStatusMutation.isPending) {
      return
    }

    const action = isAvailable ? 'GO_OFFLINE' : 'GO_ONLINE'
    setStatusError(null)
    operatingStatusMutation.mutate(
      { data: { action } },
      {
        onSuccess: async (response) => {
          queryClient.removeQueries({ queryKey: getGetRiderSessionQueryKey() })
          if (action === 'GO_ONLINE') {
            // 콜 목록으로 이동한 뒤 캐시를 갱신한다. 이동 전에 setQueryData 하면
            // 홈이 AVAILABLE 상태로 잠깐 리렌더돼 "운행 중" 화면이 깜빡인다.
            await router.navigate({ to: '/rider/requests' })
            queryClient.setQueryData(getGetRiderOperatingStatusQueryKey(), response)
          } else {
            queryClient.setQueryData(getGetRiderOperatingStatusQueryKey(), response)
            await router.invalidate()
          }
        },
        onError: (error) => {
          setStatusError(getOperatingStatusErrorMessage(error, action))
          void operatingStatusQuery.refetch()
        },
      },
    )
  }

  if (operatingStatusQuery.isPending || operatingStatus === 'BUSY') {
    return <RiderHomeFeedback icon="progress_activity" message="라이더 상태를 확인하고 있어요." spin />
  }

  if (operatingStatusQuery.isError || !operatingStatus) {
    return (
      <RiderHomeFeedback
        icon="cloud_off"
        message={getOperatingStatusQueryErrorMessage(operatingStatusQuery.error)}
        onRetry={() => void operatingStatusQuery.refetch()}
      />
    )
  }

  return (
    <div className="bg-background text-on-background flex min-h-screen flex-col">
      <header className="sticky top-0 z-50 w-full bg-surface">
        <div className="flex h-[64px] items-center justify-between px-container-margin">
          <h1 className="font-headline-lg text-headline-lg font-bold tracking-tight text-on-surface">Quick</h1>
          <button
            type="button"
            aria-label="설정"
            onClick={() => void router.navigate({ to: '/account/settings' })}
            className="flex items-center justify-center rounded-full p-2 text-secondary transition-colors duration-150 hover:bg-surface-container active:scale-95"
          >
            <span className="material-symbols-outlined" aria-hidden="true">settings</span>
          </button>
        </div>
      </header>

      <main aria-label="라이더 홈" className="flex flex-grow flex-col items-center justify-center px-container-margin py-xl pb-32">
        <div className="flex w-full max-w-md flex-col items-center space-y-lg text-center">
          <div className="mb-lg space-y-sm">
            <h2 className="font-headline-md text-headline-md text-on-surface">반갑습니다!</h2>
            <p className="font-body-lg text-body-lg text-secondary">
              {isAvailable ? '퀵 운행 중입니다. 새로운 콜을 확인해 보세요.' : '퀵을 시작하고 콜을 확인해 보세요.'}
            </p>
          </div>

          <div className="relative mb-xl flex h-48 w-full max-w-xs items-center justify-center overflow-hidden rounded-xl bg-primary-fixed shadow-sm">
            <div className="relative flex h-28 w-28 items-center justify-center rounded-full bg-surface-container-lowest shadow-md">
              <span className="material-symbols-outlined text-6xl text-primary" aria-hidden="true">two_wheeler</span>
            </div>
            <span className="absolute bottom-4 right-4 rounded-full bg-surface-container-lowest px-3 py-1 font-label-md text-label-md text-on-surface shadow-sm">
              {isAvailable ? '콜 받는 중' : '운행 대기'}
            </span>
          </div>

          <button
            type="button"
            onClick={toggleOperating}
            disabled={operatingStatusMutation.isPending}
            aria-busy={operatingStatusMutation.isPending}
            aria-pressed={isAvailable}
            className={`flex h-[64px] w-full items-center justify-center space-x-2 rounded-[16px] font-headline-sm text-headline-sm shadow-[0_4px_12px_rgba(0,0,0,0.08)] transition-colors active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-60 ${
              isAvailable
                ? 'bg-surface-container-high text-on-surface hover:bg-surface-container-highest'
                : 'bg-primary-container text-on-primary-container hover:bg-primary-fixed'
            }`}
          >
            <span className="material-symbols-outlined font-bold" aria-hidden="true">{isAvailable ? 'toggle_on' : 'toggle_off'}</span>
            <span>
              {operatingStatusMutation.isPending
                ? (isAvailable ? '운행 종료 중…' : '운행 시작 중…')
                : (isAvailable ? '퀵 종료하기' : '퀵 시작하기')}
            </span>
          </button>

          {statusError && (
            <p role="alert" className="w-full rounded-xl bg-error-container px-4 py-3 text-body-md text-on-error-container">
              {statusError}
            </p>
          )}

          {isAvailable && (
            <button
              type="button"
              onClick={() => void router.navigate({ to: '/rider/requests' })}
              className="flex h-[56px] w-full items-center justify-center space-x-2 rounded-[16px] bg-tertiary font-headline-sm text-title-md text-on-tertiary shadow-sm transition-opacity hover:opacity-90 active:scale-[0.98]"
            >
              <span className="material-symbols-outlined" aria-hidden="true">list_alt</span>
              <span>콜 목록 보기</span>
            </button>
          )}

          <div className="grid w-full grid-cols-2 gap-gutter">
            <button
              type="button"
              onClick={() => void router.navigate({ to: '/rider/history' })}
              className="flex h-[52px] items-center justify-center space-x-2 rounded-xl border border-surface-container bg-surface-container-lowest font-label-lg text-on-surface transition-colors hover:bg-surface-bright active:scale-[0.98]"
            >
              <span className="material-symbols-outlined text-secondary" aria-hidden="true">history</span>
              <span>배송 기록</span>
            </button>
            <button
              type="button"
              onClick={() => void router.navigate({ to: '/rider/points' })}
              className="flex h-[52px] items-center justify-center space-x-2 rounded-xl border border-surface-container bg-surface-container-lowest font-label-lg text-on-surface transition-colors hover:bg-surface-bright active:scale-[0.98]"
            >
              <span className="material-symbols-outlined text-secondary" aria-hidden="true">account_balance_wallet</span>
              <span>포인트</span>
            </button>
          </div>
        </div>
      </main>
    </div>
  )
}
function RiderHomeFeedback({
  icon,
  message,
  spin = false,
  onRetry,
}: {
  icon: string
  message: string
  spin?: boolean
  onRetry?: () => void
}) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-background px-container-margin text-center">
      <div className="flex max-w-sm flex-col items-center gap-4">
        <span className={`material-symbols-outlined text-5xl text-secondary ${spin ? 'animate-spin' : ''}`} aria-hidden="true">{icon}</span>
        <p role={onRetry ? 'alert' : 'status'} className="font-body-lg text-body-lg text-on-surface">{message}</p>
        {onRetry && (
          <button type="button" onClick={onRetry} className="rounded-xl bg-primary-container px-6 py-3 font-label-lg text-on-primary-container">
            다시 시도
          </button>
        )}
      </div>
    </main>
  )
}
