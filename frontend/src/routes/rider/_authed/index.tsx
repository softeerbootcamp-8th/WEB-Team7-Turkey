import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import { useChangeOperatingStatus } from '@/api/generated/rider-operating-status/rider-operating-status'
import { getGetRiderSessionQueryKey } from '@/api/generated/rider-session/rider-session'
import { getOperatingStatusErrorMessage } from './-riderHome'

export const Route = createFileRoute('/rider/_authed/')({
  component: RiderHome,
})

function RiderHome() {
  const router = useRouter()
  const queryClient = useQueryClient()
  const { session } = Route.useRouteContext()
  const [statusError, setStatusError] = useState<string | null>(null)
  const operatingStatusMutation = useChangeOperatingStatus()
  const operatingStatus = session.role === 'RIDER'
    ? (session.rider.operatingStatus ?? 'UNAVAILABLE')
    : 'UNAVAILABLE'
  const isAvailable = operatingStatus === 'AVAILABLE'
  const isBusy = operatingStatus === 'BUSY'

  function toggleOperating() {
    if (operatingStatusMutation.isPending || isBusy) {
      return
    }

    const action = isAvailable ? 'GO_OFFLINE' : 'GO_ONLINE'
    setStatusError(null)
    operatingStatusMutation.mutate(
      { data: { action } },
      {
        onSuccess: async () => {
          queryClient.removeQueries({ queryKey: getGetRiderSessionQueryKey() })
          await router.invalidate()
          if (action === 'GO_ONLINE') {
            await router.navigate({ to: '/rider/requests' })
          }
        },
        onError: (error) => {
          setStatusError(getOperatingStatusErrorMessage(error, action))
        },
      },
    )
  }

  return (
    <div className="bg-background text-on-background flex flex-col min-h-screen">
      {/* Top App Bar */}
      <header className="w-full top-0 sticky bg-surface dark:bg-surface-dim z-50">
        <div className="flex items-center justify-between px-container-margin h-[64px]">
          <h1 className="font-headline-lg text-headline-lg font-bold text-on-surface dark:text-on-surface tracking-tight">Quick</h1>
          <button className="p-2 hover:bg-surface-container dark:hover:bg-surface-container-high transition-colors rounded-full active:scale-95 duration-150 flex items-center justify-center text-secondary dark:text-secondary-fixed-dim">
            <span className="material-symbols-outlined" data-icon="settings">settings</span>
          </button>
        </div>
      </header>
      {/* Main Content */}
      <main aria-label="라이더 홈" className="flex-grow flex flex-col items-center justify-center px-container-margin py-xl pb-32">
        <div className="w-full max-w-md text-center space-y-lg flex flex-col items-center">
          {/* Welcome Text */}
          <div className="space-y-sm mb-lg">
            <h2 className="font-headline-md text-headline-md text-on-surface">반갑습니다!</h2>
            <p className="font-body-lg text-body-lg text-secondary">
              {isBusy ? '현재 배송을 진행하고 있어요.' : isAvailable ? '퀵 운행 중입니다.' : '퀵을 시작하고 콜을 확인해 보세요.'}
            </p>
          </div>
          {/* Illustration/Hero Image */}
          <div className="w-full max-w-xs h-48 mb-xl rounded-xl overflow-hidden shadow-sm bg-surface-container-highest relative flex items-center justify-center">
            {/* TODO: 실제 이미지 연결 (오토바이 배달 서비스 이미지) */}
            <div className="object-cover w-full h-full bg-surface-container-high"></div>
          </div>
          {/* Primary Action */}
          <button
            type="button"
            onClick={toggleOperating}
            disabled={operatingStatusMutation.isPending || isBusy}
            aria-busy={operatingStatusMutation.isPending}
            aria-pressed={isAvailable || isBusy}
            className={`w-full h-[64px] font-headline-sm text-headline-sm rounded-[16px] shadow-[0_4px_12px_rgba(0,0,0,0.08)] flex items-center justify-center space-x-2 transition-colors active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-60 ${
              isAvailable || isBusy
                ? 'bg-surface-container-high text-on-surface hover:bg-surface-container-highest'
                : 'bg-primary-container text-on-primary-container hover:bg-primary-fixed'
            }`}
          >
            <span className="material-symbols-outlined font-bold">{isAvailable || isBusy ? 'toggle_on' : 'toggle_off'}</span>
            <span>
              {operatingStatusMutation.isPending
                ? (isAvailable ? '운행 종료 중…' : '운행 시작 중…')
                : (isAvailable || isBusy ? '퀵 종료하기' : '퀵 시작하기')}
            </span>
          </button>
          {isBusy && (
            <p className="w-full text-body-md text-secondary">배송을 완료한 뒤 퀵을 종료할 수 있습니다.</p>
          )}
          {statusError && (
            <p role="alert" className="w-full rounded-xl bg-error-container px-4 py-3 text-body-md text-on-error-container">
              {statusError}
            </p>
          )}
          {isAvailable && (
            <button
              type="button"
              onClick={() => void router.navigate({ to: '/rider/requests' })}
              className="w-full h-[56px] bg-tertiary text-on-tertiary font-headline-sm text-title-md rounded-[16px] shadow-sm flex items-center justify-center space-x-2 hover:opacity-90 transition-opacity active:scale-[0.98]"
            >
              <span className="material-symbols-outlined">list_alt</span>
              <span>콜 목록 보기</span>
            </button>
          )}
          {isBusy && (
            <button
              type="button"
              onClick={() => void router.navigate({ to: '/rider/delivery' })}
              className="w-full h-[56px] bg-primary-container text-on-primary-container font-headline-sm text-title-md rounded-[16px] shadow-sm flex items-center justify-center space-x-2 hover:bg-primary-fixed transition-colors active:scale-[0.98]"
            >
              <span className="material-symbols-outlined">local_shipping</span>
              <span>진행 배송 보기</span>
            </button>
          )}
          {/* Secondary Action */}
          <button className="w-full h-[52px] bg-surface-container-lowest text-on-surface font-label-lg text-label-lg rounded-xl border border-surface-container flex items-center justify-center space-x-2 hover:bg-surface-bright transition-colors active:scale-[0.98]">
            <span className="material-symbols-outlined text-secondary" style={{ fontVariationSettings: '"FILL" 0' }}>search</span>
            <span className="">배송 이력 조회하기</span>
          </button>
        </div>
      </main>
      {/* Bottom Navigation Bar */}
    </div>
  )
}
