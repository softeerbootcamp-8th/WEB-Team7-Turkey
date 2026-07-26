import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/customer/_authed')({
  component: RouteComponent,
})

function RouteComponent() {
  return <div>Hello "/customer/_authed"!</div>
}
