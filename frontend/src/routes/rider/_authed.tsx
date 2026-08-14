import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, Outlet, redirect, useRouter } from '@tanstack/react-router'
import { useChangeRiderOperatingStatus } from '@/api/generated/rider-operating-status/rider-operating-status'
import { getGetRiderSessionQueryKey } from '@/api/generated/rider-session/rider-session'
import { resolveRiderGuard } from '@/shared/auth/guard'
import { ensureSessionInfo } from '@/shared/auth/session'
import { SessionErrorScreen } from '@/shared/auth/SessionErrorScreen'
import { startRiderLocationSender, useLocationSender } from '@/shared/hooks/useLocationSender'

/**
 * 라이더 전용 화면의 인증 가드. 홈은 모든 운행 상태에서 열고, 콜 목록·진행 배송 화면은
 * 현재 운행 상태와 맞을 때만 연다. 이력·정산 화면은 상태와 무관하게 열린다.
 */
export const Route = createFileRoute('/rider/_authed')({
  beforeLoad: async ({ context, location }) => {
    const session = await ensureSessionInfo(context.queryClient)
    const decision = resolveRiderGuard(session, location.href, location.pathname)

    if (decision.action === 'login-required') {
      throw redirect({ to: decision.to, search: { redirect: decision.redirectParam } })
    }
    if (decision.action === 'redirect') {
      throw redirect({ to: decision.to })
    }

    return { session }
  },
  component: RiderAuthedLayout,
  errorComponent: SessionErrorScreen,
})

function RiderAuthedLayout() {
  const { session } = Route.useRouteContext()
  const router = useRouter()
  const queryClient = useQueryClient()
  const operatingStatus =
    session.role === 'RIDER' ? (session.rider.operatingStatus ?? 'UNAVAILABLE') : 'UNAVAILABLE'

  // 위치 권한 거부 등으로 네이티브 위치 서비스 시작이 실패하면(#496) 화면을 막는다(#535) —
  // 위치 전송 없이 콜을 받고 진행하는 상태를 허용하지 않는다.
  const [locationBlocked, setLocationBlocked] = useState(false)
  const [retrying, setRetrying] = useState(false)
  const endOperatingMutation = useChangeRiderOperatingStatus()

  useLocationSender(operatingStatus, () => {
    if (operatingStatus !== 'UNAVAILABLE') {
      setLocationBlocked(true)
    }
  })

  async function retryLocationPermission() {
    if (operatingStatus === 'UNAVAILABLE' || retrying) {
      return
    }
    setRetrying(true)
    try {
      await startRiderLocationSender(operatingStatus)
      setLocationBlocked(false)
    } catch (error) {
      console.error('Android 위치 서비스를 시작하지 못했습니다.', error)
    } finally {
      setRetrying(false)
    }
  }

  function endOperatingAndLeave() {
    if (endOperatingMutation.isPending) {
      return
    }
    endOperatingMutation.mutate(
      { data: { action: 'GO_OFFLINE' } },
      {
        onSuccess: () => {
          // 홈이 최신 운행 상태로 렌더되도록 세션 캐시를 비운다 — 다음 진입 시 다시 조회된다.
          queryClient.removeQueries({ queryKey: getGetRiderSessionQueryKey() })
          setLocationBlocked(false)
          void router.navigate({ to: '/rider', replace: true })
        },
        // 운행 종료 자체가 실패하면 서버 상태가 AVAILABLE/BUSY로 남는다 — 위치 없이 계속
        // 콜을 받게 둘 수 없으므로 화면은 계속 막아 둔다.
      },
    )
  }

  if (locationBlocked) {
    return (
      <LocationPermissionRequiredScreen
        retrying={retrying}
        endingOperation={endOperatingMutation.isPending}
        onRetry={() => void retryLocationPermission()}
        onEndOperating={endOperatingAndLeave}
      />
    )
  }

  return <Outlet />
}

function LocationPermissionRequiredScreen({
  retrying,
  endingOperation,
  onRetry,
  onEndOperating,
}: {
  retrying: boolean
  endingOperation: boolean
  onRetry: () => void
  onEndOperating: () => void
}) {
  return (
    <main
      aria-label="위치 권한 필요"
      className="flex min-h-screen flex-col items-center justify-center gap-6 bg-background px-container-margin text-center"
    >
      <span className="material-symbols-outlined text-5xl text-error" aria-hidden="true">location_off</span>
      <div className="flex flex-col gap-2">
        <h1 className="font-headline-md text-headline-md text-on-surface">위치 권한이 필요해요</h1>
        <p className="font-body-lg text-body-lg text-secondary">
          위치 접근을 허용해야 콜을 받고 배송을 진행할 수 있어요. 기기 설정에서 이 앱의 위치 권한을
          허용한 뒤 다시 시도해 주세요.
        </p>
      </div>
      <div className="flex w-full max-w-xs flex-col gap-3">
        <button
          type="button"
          onClick={onRetry}
          disabled={retrying}
          aria-busy={retrying}
          className="flex h-14 items-center justify-center rounded-xl bg-primary-container font-headline-sm text-headline-sm text-on-primary-container disabled:cursor-not-allowed disabled:opacity-60"
        >
          {retrying ? '확인 중…' : '권한 다시 확인하기'}
        </button>
        <button
          type="button"
          onClick={onEndOperating}
          disabled={endingOperation}
          aria-busy={endingOperation}
          className="flex h-12 items-center justify-center rounded-xl border border-surface-container font-label-lg text-on-surface disabled:cursor-not-allowed disabled:opacity-60"
        >
          {endingOperation ? '운행 종료 중…' : '운행 종료하고 나가기'}
        </button>
      </div>
    </main>
  )
}
