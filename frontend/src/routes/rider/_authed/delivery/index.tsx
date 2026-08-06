import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import {
  getGetCurrentRiderDeliveryQueryKey,
  useGetCurrentRiderDelivery,
  useTransitionRiderDelivery,
} from '@/api/generated/rider-delivery/rider-delivery'
import type {
  AddressResponse,
  RiderDeliveryResponse,
  RiderDeliveryTransitionRequestAction,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { RequestRouteMap } from '../requests/$deliveryId/-components/RequestRouteMap'
import {
  formatAddress,
  formatDeliveryAmount,
  formatDeliveryDistance,
  getDeliveryErrorMessage,
  getDeliveryStageContent,
  getKakaoNavigationUrl,
  getTransitionErrorMessage,
} from './-delivery'

export const Route = createFileRoute('/rider/_authed/delivery/')({
  component: RiderDelivery,
})

function RiderDelivery() {
  const router = useRouter()
  const queryClient = useQueryClient()
  const [transitionError, setTransitionError] = useState<string | null>(null)
  const currentDeliveryQuery = useGetCurrentRiderDelivery({ query: { retry: false } })
  const transitionMutation = useTransitionRiderDelivery()
  const delivery = currentDeliveryQuery.data?.data
  const stage = getDeliveryStageContent(delivery?.status)

  function transition(action: RiderDeliveryTransitionRequestAction) {
    if (!delivery?.deliveryId || transitionMutation.isPending) {
      return
    }

    setTransitionError(null)
    transitionMutation.mutate(
      { deliveryId: delivery.deliveryId, data: { action } },
      {
        onSuccess: (response) => {
          queryClient.setQueryData(getGetCurrentRiderDeliveryQueryKey(), response)
        },
        onError: (error) => {
          setTransitionError(getTransitionErrorMessage(error))
          void currentDeliveryQuery.refetch()
        },
      },
    )
  }

  if (currentDeliveryQuery.isPending) {
    return <DeliveryFeedback icon="progress_activity" message="진행 중 배송을 복구하고 있어요." spin />
  }

  if (currentDeliveryQuery.isError) {
    return (
      <DeliveryFeedback
        icon="cloud_off"
        message={getDeliveryErrorMessage(currentDeliveryQuery.error)}
        onRetry={() => void currentDeliveryQuery.refetch()}
      />
    )
  }

  if (!delivery || !stage) {
    return (
      <DeliveryFeedback
        icon="local_shipping"
        message="현재 진행 중인 배송이 없습니다."
        actionLabel="라이더 홈으로"
        onAction={() => void router.navigate({ to: '/rider' })}
      />
    )
  }

  const navigationAddress = stage.navigationTarget === 'pickup' ? delivery.pickup : delivery.destination
  const navigationUrl = getKakaoNavigationUrl(navigationAddress)
  const transitionAction = delivery.nextAction === 'COMPLETE' ? null : delivery.nextAction
  const needsCompletionProof = delivery.nextAction === 'COMPLETE'

  return (
    <main aria-label="진행 중 배송" className="mx-auto flex min-h-screen w-full max-w-md flex-col bg-background text-on-background shadow-[0_0_10px_rgba(0,0,0,0.08)]">
      <header className="sticky top-0 z-30 flex min-h-16 items-center justify-between border-b border-surface-container bg-surface px-container-margin">
        <div>
          <p className="font-label-sm text-label-sm text-secondary">배송 #{delivery.deliveryId}</p>
          <h1 className="font-headline-sm text-headline-sm font-bold text-on-surface">{stage.title}</h1>
        </div>
        <span className="rounded-full bg-primary-container px-3 py-1 font-label-sm text-label-sm text-on-primary-container">
          {stage.statusLabel}
        </span>
      </header>

      <section aria-label="배송 경로 지도" className="h-64 shrink-0 bg-surface-container-high">
        <RequestRouteMap pickup={delivery.pickup} destination={delivery.destination} />
      </section>

      <div className="flex flex-1 flex-col gap-md px-container-margin py-lg pb-32">
        <section aria-labelledby="delivery-guide" className="rounded-xl bg-primary-fixed p-md">
          <h2 id="delivery-guide" className="font-headline-md text-headline-md font-bold text-on-primary-fixed">{stage.guide}</h2>
          <p className="mt-1 font-body-md text-body-md text-on-primary-fixed-variant">{stage.description}</p>
        </section>

        <section aria-label="배송 장소" className="overflow-hidden rounded-xl border border-surface-container bg-surface-container-lowest">
          <DeliveryAddressRow
            label="픽업"
            icon="storefront"
            address={delivery.pickup}
            contactName={delivery.sender?.name}
            phoneNumber={delivery.sender?.phoneNumber}
            active={stage.navigationTarget === 'pickup'}
          />
          <div className="mx-md border-t border-dashed border-surface-container" />
          <DeliveryAddressRow
            label="도착"
            icon="home"
            address={delivery.destination}
            contactName={delivery.recipient?.name}
            phoneNumber={delivery.recipient?.phoneNumber}
            active={stage.navigationTarget === 'destination'}
          />
        </section>

        <section aria-label="배송 정보" className="grid grid-cols-2 gap-gutter">
          <InfoCard label="물품" value={formatItemType(delivery.itemType)} icon="package_2" />
          <InfoCard label="배송 거리" value={formatDeliveryDistance(delivery.straightDistanceMeters)} icon="route" />
          <div className="col-span-2">
            <InfoCard label="예상 정산액" value={formatDeliveryAmount(delivery.expectedSettlementAmount)} icon="payments" />
          </div>
        </section>

        {needsCompletionProof && (
          <p role="status" className="rounded-xl bg-tertiary-fixed p-md font-body-md text-body-md text-on-tertiary-fixed-variant">
            배송 완료 처리를 위해 수령 인증정보를 등록해 주세요.
          </p>
        )}
        {transitionError && (
          <p role="alert" className="rounded-xl bg-error-container p-md font-body-md text-body-md text-on-error-container">
            {transitionError}
          </p>
        )}
      </div>

      <footer className="fixed inset-x-0 bottom-0 z-40 mx-auto flex h-20 w-full max-w-md bg-surface shadow-[0_-4px_12px_rgba(0,0,0,0.08)]">
        <button
          type="button"
          disabled={!navigationUrl}
          onClick={() => navigationUrl && window.open(navigationUrl, '_blank', 'noopener,noreferrer')}
          className="flex w-28 flex-col items-center justify-center gap-1 bg-surface-container-high font-label-md text-on-surface disabled:cursor-not-allowed disabled:opacity-50"
        >
          <span className="material-symbols-outlined" aria-hidden="true">navigation</span>
          <span>길안내</span>
        </button>
        {transitionAction ? (
          <button
            type="button"
            disabled={transitionMutation.isPending}
            aria-busy={transitionMutation.isPending}
            onClick={() => transition(transitionAction)}
            className="flex flex-1 items-center justify-center bg-primary-container px-4 font-headline-sm text-headline-sm font-bold text-on-primary-container disabled:cursor-not-allowed disabled:opacity-60"
          >
            {transitionMutation.isPending ? '처리 중…' : stage.actionLabel}
          </button>
        ) : needsCompletionProof ? (
          <button
            type="button"
            onClick={() => void router.navigate({
              to: '/rider/delivery/$deliveryId/complete',
              params: { deliveryId: String(delivery.deliveryId) },
            })}
            className="flex flex-1 items-center justify-center bg-primary-container px-4 text-center font-headline-sm text-headline-sm font-bold text-on-primary-container transition-colors hover:bg-primary-fixed active:scale-[0.99]"
          >
            배달인증하기
          </button>
        ) : (
          <div role="alert" className="flex flex-1 items-center justify-center bg-error-container px-4 text-center font-label-lg text-on-error-container">
            다음 행동 정보 없음
          </div>
        )}
      </footer>
    </main>
  )
}

function DeliveryAddressRow({
  label,
  icon,
  address,
  contactName,
  phoneNumber,
  active,
}: {
  label: string
  icon: string
  address?: AddressResponse
  contactName?: string
  phoneNumber?: string
  active: boolean
}) {
  return (
    <div className="flex gap-3 p-md">
      <span className={`material-symbols-outlined mt-0.5 ${active ? 'text-primary' : 'text-secondary'}`} aria-hidden="true">{icon}</span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="font-label-sm text-label-sm text-secondary">{label}</span>
          {active && <span className="rounded-full bg-primary-fixed px-2 py-0.5 text-label-sm text-on-primary-fixed">현재 목적지</span>}
        </div>
        <p className="mt-1 break-words font-body-lg text-body-lg font-bold text-on-surface">{formatAddress(address)}</p>
        {(contactName || phoneNumber) && (
          <div className="mt-2 flex items-center justify-between gap-2">
            <span className="font-body-md text-body-md text-secondary">{contactName ?? '연락처'} · {phoneNumber ?? '번호 미제공'}</span>
            {phoneNumber && (
              <a href={`tel:${phoneNumber}`} aria-label={`${label} 연락처로 전화`} className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-surface-container-high text-on-surface">
                <span className="material-symbols-outlined" aria-hidden="true">call</span>
              </a>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

function InfoCard({ label, value, icon }: { label: string; value: string; icon: string }) {
  return (
    <div className="flex h-full items-center gap-3 rounded-xl bg-surface-container-lowest p-md">
      <span className="material-symbols-outlined text-secondary" aria-hidden="true">{icon}</span>
      <div className="min-w-0">
        <p className="font-label-sm text-label-sm text-secondary">{label}</p>
        <p className="truncate font-body-lg text-body-lg font-bold text-on-surface">{value}</p>
      </div>
    </div>
  )
}

function DeliveryFeedback({
  icon,
  message,
  spin = false,
  onRetry,
  actionLabel,
  onAction,
}: {
  icon: string
  message: string
  spin?: boolean
  onRetry?: () => void
  actionLabel?: string
  onAction?: () => void
}) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-background px-container-margin text-center">
      <div className="flex max-w-sm flex-col items-center gap-4">
        <span className={`material-symbols-outlined text-5xl text-secondary ${spin ? 'animate-spin' : ''}`} aria-hidden="true">{icon}</span>
        <p role={onRetry ? 'alert' : 'status'} className="font-body-lg text-body-lg text-on-surface">{message}</p>
        {onRetry && <button type="button" onClick={onRetry} className="rounded-xl bg-primary-container px-6 py-3 font-label-lg text-on-primary-container">다시 시도</button>}
        {actionLabel && onAction && <button type="button" onClick={onAction} className="rounded-xl bg-primary-container px-6 py-3 font-label-lg text-on-primary-container">{actionLabel}</button>}
      </div>
    </main>
  )
}

function formatItemType(itemType?: RiderDeliveryResponse['itemType']): string {
  const labels: Record<NonNullable<RiderDeliveryResponse['itemType']>, string> = {
    DOCUMENT: '서류',
    SMALL_PARCEL: '소형 물품',
    MEDIUM_PARCEL: '중형 물품',
    LARGE_PARCEL: '대형 물품',
    FOOD: '음식',
  }
  return itemType ? labels[itemType] : '정보 미제공'
}
