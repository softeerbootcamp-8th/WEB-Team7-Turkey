import { createFileRoute, Outlet, redirect } from '@tanstack/react-router'
import { resolveRiderGuard } from '@/shared/auth/guard'
import { ensureSessionInfo } from '@/shared/auth/session'
import { SessionErrorScreen } from '@/shared/auth/SessionErrorScreen'
import { useLocationSender } from '@/shared/hooks/useLocationSender'

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
  const operatingStatus =
    session.role === 'RIDER' ? (session.rider.operatingStatus ?? 'UNAVAILABLE') : 'UNAVAILABLE'

  useLocationSender(operatingStatus)
  return <Outlet />
}
