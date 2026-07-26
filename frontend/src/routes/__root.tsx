import { createRootRouteWithContext, Outlet } from '@tanstack/react-router'
import type { QueryClient } from '@tanstack/react-query'

interface RouterContext {
  queryClient: QueryClient
}

export const Route = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
})

function RootLayout() {
  // 전역 레이아웃. 모바일 셸 폭(max-w-md)으로 고정하고 하위 라우트를 렌더한다.
  return (
    <div className="mx-auto min-h-screen max-w-md bg-surface">
      <Outlet />
    </div>
  )
}
