import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/auth/find-account/')({
  component: RouteComponent,
})

function RouteComponent() {
  return <div>Hello "/auth/find-account/"!</div>
}
