import { createFileRoute, Outlet, redirect } from '@tanstack/react-router'
import { resolveGuestGuard, type SessionInfo } from '@/shared/auth/guard'
import { ensureSessionInfo } from '@/shared/auth/session'

/**
 * `auth/*`(로그인 전 화면 — 가입·계정 찾기·인증)의 **비인증** 가드.
 *
 * 파일을 옮기지 않고 `/auth` 레이아웃 라우트로 가드를 얹는다(이슈: `auth/*` 는 이동하지 않는다).
 *
 * 인증 가드와 달리 세션 확인이 실패하면 **열어준다.** 로그인하지 않은 사람이 가입하려는 화면인데
 * 세션 조회 장애를 이유로 막으면 아무도 가입할 수 없다. 잘못 열려도 손해는 "이미 로그인한 사람이
 * 가입 화면을 본다" 정도다.
 */
export const Route = createFileRoute('/auth')({
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
  component: Outlet,
})
