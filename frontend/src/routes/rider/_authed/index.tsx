import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import { useChangeOperatingStatus } from '@/api/generated/rider-operating-status/rider-operating-status'
import { getGetRiderSessionQueryKey } from '@/api/generated/rider-session/rider-session'
import { getGoOnlineErrorMessage } from './-riderHome'

export const Route = createFileRoute('/rider/_authed/')({
  component: RiderHome,
})

function RiderHome() {
  const router = useRouter()
  const queryClient = useQueryClient()
  const [startError, setStartError] = useState<string | null>(null)
  const operatingStatusMutation = useChangeOperatingStatus()

  function startOperating() {
    if (operatingStatusMutation.isPending) {
      return
    }

    setStartError(null)
    operatingStatusMutation.mutate(
      { data: { action: 'GO_ONLINE' } },
      {
        onSuccess: async () => {
          queryClient.removeQueries({ queryKey: getGetRiderSessionQueryKey() })
          await router.navigate({ to: '/rider/requests' })
        },
        onError: (error) => {
          setStartError(getGoOnlineErrorMessage(error))
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
            <p className="font-body-lg text-body-lg text-secondary">안전 운행하세요</p>
          </div>
          {/* Illustration/Hero Image */}
          <div className="w-full max-w-xs h-48 mb-xl rounded-xl overflow-hidden shadow-sm bg-surface-container-highest relative flex items-center justify-center">
            {/* TODO: 실제 이미지 연결 (오토바이 배달 서비스 이미지) */}
            <div className="object-cover w-full h-full bg-surface-container-high"></div>
          </div>
          {/* Primary Action */}
          <button
            type="button"
            onClick={startOperating}
            disabled={operatingStatusMutation.isPending}
            aria-busy={operatingStatusMutation.isPending}
            className="w-full h-[64px] bg-primary-container text-on-primary-container font-headline-sm text-headline-sm rounded-[16px] shadow-[0_4px_12px_rgba(0,0,0,0.08)] flex items-center justify-center space-x-2 hover:bg-primary-fixed transition-colors active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-60"
          >
            <span className="material-symbols-outlined font-bold">local_shipping</span>
            <span>{operatingStatusMutation.isPending ? '운행 시작 중…' : '퀵 시작하기'}</span>
          </button>
          {startError && (
            <p role="alert" className="w-full rounded-xl bg-error-container px-4 py-3 text-body-md text-on-error-container">
              {startError}
            </p>
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
