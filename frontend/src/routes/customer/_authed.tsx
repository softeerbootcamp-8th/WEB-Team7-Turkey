import { createFileRoute, Outlet, redirect } from '@tanstack/react-router'
import { resolveCustomerGuard } from '@/shared/auth/guard'
import { ensureSessionInfo } from '@/shared/auth/session'
import { SessionErrorScreen } from '@/shared/auth/SessionErrorScreen'

/**
 * 고객 전용 화면의 인증 가드. 경로 없는(pathless) 레이아웃이라 하위 라우트의 URL 은 바뀌지 않는다.
 *
 * 세션 확인 실패(네트워크·5xx)는 여기서 던져져 errorComponent 가 받는다 — 만료로 오해해
 * 로그인 화면으로 보내지 않는다.
 */
export const Route = createFileRoute('/customer/_authed')({
  beforeLoad: async ({ context, location }) => {
    const session = await ensureSessionInfo(context.queryClient)
    const decision = resolveCustomerGuard(session, location.href)

    if (decision.action === 'login-required') {
      throw redirect({ to: decision.to, search: { redirect: decision.redirectParam } })
    }
    if (decision.action === 'redirect') {
      throw redirect({ to: decision.to })
    }

    // 하위 라우트가 다시 조회하지 않도록 컨텍스트로 내려준다.
    return { session }
  },
  component: Outlet,
  errorComponent: SessionErrorScreen,
})
