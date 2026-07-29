import { createFileRoute } from '@tanstack/react-router'
import { validateRedirectSearch } from '@/shared/auth/redirectSearch'

export const Route = createFileRoute('/')({
  // account/* 는 역할 무관 화면이라 비로그인 시 여기로 보내진다(#195). 목적지를 보존한다.
  validateSearch: validateRedirectSearch,
  component: RouteComponent,
})

function RouteComponent() {
  return <div>Hello "/"!</div>
}
