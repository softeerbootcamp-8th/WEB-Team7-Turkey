import { createFileRoute } from '@tanstack/react-router'
import { DeliveryForm } from './-components/DeliveryForm'

export const Route = createFileRoute('/customer/_authed/deliveries/new')({
  component: NewDelivery,
})

function NewDelivery() {
  const { session } = Route.useRouteContext()
  // 보내는 분(sender)은 로그인한 고객 본인이라 DB에 있는 이름으로 자동입력한다(#533).
  // 받는 분(recipient)은 주문마다 다른 사람이라 자동입력 대상이 아니다.
  const defaultSenderName = session.role === 'CUSTOMER' ? session.customer.name : undefined

  return <DeliveryForm defaultSenderName={defaultSenderName} />
}
