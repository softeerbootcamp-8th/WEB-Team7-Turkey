import { useState } from 'react'
import { createFileRoute, Link, useRouter } from '@tanstack/react-router'
import { useGetDeliveries } from '@/api/generated/customer-delivery/customer-delivery'
import type { DeliverySummaryResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { getCustomerDeliveryStatusLabel, isActiveDeliveryStatus } from '@/shared/delivery/status'
import {
  formatDeliveryFare,
  formatDeliveryRequestedAt,
  getDeliveryListErrorMessage,
  partitionDeliveries,
} from './-deliveryList'

const PAGE_SIZE = 20

export const Route = createFileRoute('/customer/_authed/deliveries/')({
  component: DeliveryHistory,
})

function DeliveryHistory() {
  const router = useRouter()
  const [page, setPage] = useState(0)
  const deliveriesQuery = useGetDeliveries(
    { page, size: PAGE_SIZE },
    { query: { retry: false } },
  )
  const response = deliveriesQuery.data?.data
  const deliveries = response?.items ?? []
  const { active, past } = partitionDeliveries(deliveries)
  const hasInvalidResponse = deliveriesQuery.isSuccess && response == null
  const totalElements = response?.totalElements ?? 0
  const hasPrevious = page > 0
  const hasNext = (page + 1) * PAGE_SIZE < totalElements

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-md flex-col bg-surface text-on-surface shadow-sm">
      <header className="sticky top-0 z-20 flex h-16 items-center border-b border-outline-variant bg-surface-container-lowest px-4">
        <button
          type="button"
          aria-label="고객 홈으로 돌아가기"
          onClick={() => void router.navigate({ to: '/customer' })}
          className="flex h-11 w-11 items-center justify-center rounded-full text-on-surface transition-colors hover:bg-surface-container-low"
        >
          <span className="material-symbols-outlined" aria-hidden="true">arrow_back</span>
        </button>
        <h1 className="flex-1 text-center text-headline-md font-bold">배송 내역</h1>
        <button
          type="button"
          aria-label="배송 내역 다시 불러오기"
          onClick={() => void deliveriesQuery.refetch()}
          disabled={deliveriesQuery.isFetching}
          className="flex h-11 w-11 items-center justify-center rounded-full text-on-surface transition-colors hover:bg-surface-container-low disabled:opacity-50"
        >
          <span className={`material-symbols-outlined ${deliveriesQuery.isFetching ? 'animate-spin' : ''}`} aria-hidden="true">refresh</span>
        </button>
      </header>

      <main aria-label="고객 배송 목록" className="flex flex-1 flex-col gap-6 px-5 py-6">
        {deliveriesQuery.isPending ? (
          <Feedback icon="progress_activity" message="배송 내역을 불러오고 있어요." spin />
        ) : deliveriesQuery.isError || hasInvalidResponse ? (
          <Feedback
            alert
            icon="cloud_off"
            message={deliveriesQuery.isError
              ? getDeliveryListErrorMessage(deliveriesQuery.error)
              : '배송 내역을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'}
            onRetry={() => void deliveriesQuery.refetch()}
          />
        ) : deliveries.length === 0 ? (
          <section className="flex min-h-80 flex-col items-center justify-center gap-4 rounded-2xl bg-surface-container-lowest px-6 py-12 text-center">
            <span className="material-symbols-outlined text-5xl text-outline" aria-hidden="true">inbox</span>
            <div>
              <h2 className="text-body-lg font-bold">배송 내역이 없습니다</h2>
              <p className="mt-1 text-body-md text-secondary">첫 배송을 요청해 보세요.</p>
            </div>
            <Link
              to="/customer/deliveries/new"
              className="mt-2 rounded-xl bg-primary-container px-5 py-3 text-label-lg font-bold text-on-primary-container"
            >
              퀵 부르기
            </Link>
          </section>
        ) : (
          <>
            {deliveriesQuery.isFetching && (
              <p aria-live="polite" className="-mb-3 text-right text-label-sm text-secondary">업데이트 중…</p>
            )}
            {active.length > 0 && (
              <DeliverySection title="진행 중인 배송" deliveries={active} active />
            )}
            {past.length > 0 && (
              <DeliverySection title="지난 배송" deliveries={past} />
            )}
            {totalElements > PAGE_SIZE && (
              <nav aria-label="배송 내역 페이지" className="flex items-center justify-between gap-3 pt-2">
                <button
                  type="button"
                  disabled={!hasPrevious || deliveriesQuery.isFetching}
                  onClick={() => setPage(current => Math.max(0, current - 1))}
                  className="flex min-h-12 flex-1 items-center justify-center gap-1 rounded-xl border border-outline-variant bg-surface-container-lowest text-label-lg font-bold disabled:opacity-40"
                >
                  <span className="material-symbols-outlined text-lg" aria-hidden="true">chevron_left</span>
                  이전
                </button>
                <span className="min-w-16 text-center text-label-md text-secondary">{page + 1} 페이지</span>
                <button
                  type="button"
                  disabled={!hasNext || deliveriesQuery.isFetching}
                  onClick={() => setPage(current => current + 1)}
                  className="flex min-h-12 flex-1 items-center justify-center gap-1 rounded-xl border border-outline-variant bg-surface-container-lowest text-label-lg font-bold disabled:opacity-40"
                >
                  다음
                  <span className="material-symbols-outlined text-lg" aria-hidden="true">chevron_right</span>
                </button>
              </nav>
            )}
          </>
        )}
      </main>
    </div>
  )
}

function DeliverySection({
  title,
  deliveries,
  active = false,
}: {
  title: string
  deliveries: DeliverySummaryResponse[]
  active?: boolean
}) {
  return (
    <section aria-label={title} className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="text-headline-sm font-bold">{title}</h2>
        <span className="text-label-md text-secondary">{deliveries.length}건</span>
      </div>
      <div className="flex flex-col gap-3">
        {deliveries.map((delivery, index) => (
          <DeliveryCard
            key={delivery.deliveryId ?? `${delivery.requestedAt ?? 'delivery'}-${index}`}
            delivery={delivery}
            emphasize={active}
          />
        ))}
      </div>
    </section>
  )
}

function DeliveryCard({ delivery, emphasize }: { delivery: DeliverySummaryResponse; emphasize: boolean }) {
  const hasId = delivery.deliveryId != null
  const detailLink = hasId
    ? { to: '/customer/deliveries/$deliveryId' as const, params: { deliveryId: String(delivery.deliveryId) } }
    : null
  const trackingLink = hasId && isActiveDeliveryStatus(delivery.status)
    ? { to: '/customer/deliveries/$deliveryId/tracking' as const, params: { deliveryId: String(delivery.deliveryId) } }
    : null

  return (
    <article className={`overflow-hidden rounded-2xl border bg-surface-container-lowest shadow-sm ${emphasize ? 'border-tertiary' : 'border-outline-variant'}`}>
      <div className="p-5">
        <div className="flex items-start justify-between gap-3">
          <div>
            <span className={`inline-flex rounded-full px-3 py-1 text-label-md font-bold ${emphasize ? 'bg-tertiary-container text-on-tertiary-container' : 'bg-surface-container-high text-secondary'}`}>
              {getCustomerDeliveryStatusLabel(delivery.status)}
            </span>
            <time className="mt-2 block text-label-md text-secondary">
              {formatDeliveryRequestedAt(delivery.requestedAt)}
            </time>
          </div>
          <strong className="shrink-0 text-body-lg">{formatDeliveryFare(delivery.totalFare)}</strong>
        </div>

        <dl className="mt-5 grid grid-cols-[52px_1fr] gap-x-3 gap-y-3 text-body-md">
          <dt className="text-secondary">출발지</dt>
          <dd className="break-words font-semibold">{delivery.pickupRoadAddress || '주소 미제공'}</dd>
          <dt className="text-secondary">도착지</dt>
          <dd className="break-words font-semibold">{delivery.destinationRoadAddress || '주소 미제공'}</dd>
        </dl>
      </div>

      <div className="flex border-t border-outline-variant">
        {detailLink ? (
          <Link
            {...detailLink}
            className="flex min-h-12 flex-1 items-center justify-center gap-1 text-label-lg font-bold hover:bg-surface-container-low"
          >
            상세 보기
            <span className="material-symbols-outlined text-lg" aria-hidden="true">chevron_right</span>
          </Link>
        ) : (
          <span className="flex min-h-12 flex-1 items-center justify-center text-label-lg text-outline">상세 정보 없음</span>
        )}
        {trackingLink && (
          <Link
            {...trackingLink}
            className="flex min-h-12 flex-1 items-center justify-center gap-1 border-l border-outline-variant bg-primary-container text-label-lg font-bold text-on-primary-container hover:bg-primary-fixed"
          >
            <span className="material-symbols-outlined text-lg" aria-hidden="true">near_me</span>
            배송 추적
          </Link>
        )}
      </div>
    </article>
  )
}

function Feedback({
  icon,
  message,
  spin = false,
  alert = false,
  onRetry,
}: {
  icon: string
  message: string
  spin?: boolean
  alert?: boolean
  onRetry?: () => void
}) {
  return (
    <section
      role={alert ? 'alert' : 'status'}
      className={`flex min-h-80 flex-col items-center justify-center gap-4 rounded-2xl px-6 py-12 text-center ${alert ? 'bg-error-container text-on-error-container' : 'bg-surface-container-lowest text-secondary'}`}
    >
      <span className={`material-symbols-outlined text-5xl ${spin ? 'animate-spin' : ''}`} aria-hidden="true">{icon}</span>
      <p className="text-body-md font-semibold">{message}</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="rounded-xl bg-on-error-container px-5 py-2.5 text-label-lg font-bold text-on-error"
        >
          다시 시도
        </button>
      )}
    </section>
  )
}
