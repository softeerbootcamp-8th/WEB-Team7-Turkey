import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/account/notifications/')({
  component: RouteComponent,
})

function RouteComponent() {
  return <div>Hello "/account/notifications/"!</div>
}
