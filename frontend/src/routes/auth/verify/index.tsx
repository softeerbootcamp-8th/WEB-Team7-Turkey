import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/auth/verify/')({
  component: RouteComponent,
})

function RouteComponent() {
  return <div>Hello "/auth/verify/"!</div>
}
