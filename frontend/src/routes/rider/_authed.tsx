import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/rider/_authed')({
  component: RouteComponent,
})

function RouteComponent() {
  return <div>Hello "/rider/_authed"!</div>
}
