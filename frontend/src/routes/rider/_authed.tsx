import { createFileRoute, Outlet, redirect } from '@tanstack/react-router'
import { resolveRiderGuard } from '@/shared/auth/guard'
import { ensureSessionInfo } from '@/shared/auth/session'
import { SessionErrorScreen } from '@/shared/auth/SessionErrorScreen'

/**
 * 라이더 전용 화면의 인증 가드. 인증·역할 확인에 더해 운행 상태와 화면의 정합성까지 본다
 * (`UNAVAILABLE`→홈, `AVAILABLE`→콜 목록, `BUSY`→진행 배송). 강제 대상은 그 3개 화면끼리이고,
 * 이력·정산 화면은 상태와 무관하게 열린다 — 판정 근거는 `shared/auth/guard.ts` 참고.
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
  component: Outlet,
  errorComponent: SessionErrorScreen,
})
