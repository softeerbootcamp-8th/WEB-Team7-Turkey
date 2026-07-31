import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider, createRouter } from '@tanstack/react-router'
import { QueryClientProvider } from '@tanstack/react-query'
import { routeTree } from './routeTree.gen'
import { queryClient } from './lib/queryClient'
import { setUnauthorizedHandler } from './lib/axios'
import { clearSessionQueries } from './shared/auth/session'
import './styles/globals.css'

const router = createRouter({
  routeTree,
  context: { queryClient },
  defaultPreload: 'intent',
})

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

/**
 * 401 공통 처리 배선(#195).
 *
 * 임의 API 가 401 을 주면 세션이 끝난 것이므로 캐시된 세션을 버리고 현재 위치를 다시 평가한다.
 * `invalidate()` 를 쓰는 이유: 로그인 화면 경로를 여기서 정하면 역할을 모른 채 골라야 하지만,
 * 재평가하면 지금 있는 라우트의 가드가 자기 역할에 맞는 로그인 화면으로 목적지까지 보존해 보낸다.
 * 화면 컴포넌트는 401 을 개별 처리하지 않는다.
 */
setUnauthorizedHandler(() => {
  clearSessionQueries(queryClient)
  void router.invalidate()
})

const rootElement = document.getElementById('root')!

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
