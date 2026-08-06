import { createFileRoute } from '@tanstack/react-router'
import { DeliveryForm } from './-components/DeliveryForm'

export const Route = createFileRoute('/customer/_authed/deliveries/new')({
  component: NewDelivery,
})

function NewDelivery() {
  return <DeliveryForm />
}
