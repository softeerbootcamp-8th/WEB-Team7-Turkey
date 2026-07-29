import { createFileRoute, Outlet, redirect } from '@tanstack/react-router'
import { resolveAccountGuard } from '@/shared/auth/guard'
import { ensureSessionInfo } from '@/shared/auth/session'
import { SessionErrorScreen } from '@/shared/auth/SessionErrorScreen'

/**
 * `account/*`(회원정보·계정 설정·알림함)의 인증 가드.
 *
 * 고객·라이더 공용 화면이라 `customer/_authed` · `rider/_authed` 어느 쪽에도 넣을 수 없어
 * `/account` 레이아웃 라우트로 따로 얹는다(#195 사람 확인). 역할은 따지지 않고 로그인만 확인한다.
 *
 * 비로그인이면 역할별 로그인 화면을 고를 수 없으므로 랜딩(`/`)으로 보내고 목적지만 보존한다.
 */
export const Route = createFileRoute('/account')({
  beforeLoad: async ({ context, location }) => {
    const session = await ensureSessionInfo(context.queryClient)
    const decision = resolveAccountGuard(session, location.href)

    if (decision.action === 'login-required') {
      throw redirect({ to: decision.to, search: { redirect: decision.redirectParam } })
    }

    return { session }
  },
  component: Outlet,
  errorComponent: SessionErrorScreen,
})
