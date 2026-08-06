import { useState } from 'react'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import {
  formatDistance,
  formatHistoryDate,
  formatHistoryTime,
  formatItemType,
  getHistoryErrorMessage,
} from './-riderHistory'
import { useRiderDeliveryHistory } from './-useRiderDeliveryHistory'

export const Route = createFileRoute('/rider/_authed/history/')({
  component: RiderHistory,
})

function RiderHistory() {
  const router = useRouter()
  const [page, setPage] = useState(0)
  const historyQuery = useRiderDeliveryHistory(page)

  return (
    <main aria-label="배송 기록" className="min-h-screen bg-background pb-20 text-on-background">
      <header className="sticky top-0 z-10 flex h-14 items-center border-b border-surface-container bg-surface/95 px-container-margin backdrop-blur">
        <button
          type="button"
          aria-label="라이더 홈으로 돌아가기"
          onClick={() => void router.navigate({ to: '/rider' })}
          className="-ml-2 flex h-10 w-10 items-center justify-center rounded-full text-on-surface-variant transition-colors hover:bg-surface-container"
        >
          <span className="material-symbols-outlined" aria-hidden="true">
            arrow_back
          </span>
        </button>
        <h1 className="flex-1 text-center font-headline-md text-on-surface">배송 기록</h1>
        <div className="w-8" aria-hidden="true" />
      </header>

      <div className="mx-auto flex w-full max-w-lg flex-col">
        <section aria-label="배송 기록 목록" className="flex flex-col px-container-margin py-lg">
          <div className="mb-md flex items-end justify-between gap-3">
            <div>
              <h2 className="font-headline-sm text-on-surface">배송 기록</h2>
              <p className="mt-1 font-body-sm text-secondary">최근 완료한 배송을 확인하세요.</p>
            </div>
            {!historyQuery.isPending && !historyQuery.isError && (
              <span className="shrink-0 rounded-full bg-surface-container px-3 py-1 font-label-sm text-secondary">
                총 {historyQuery.totalElements}건
              </span>
            )}
          </div>
          {historyQuery.isPending ? (
            <HistoryFeedback icon="progress_activity" message="배송 기록을 불러오고 있어요." spin />
          ) : historyQuery.isError ? (
            <HistoryFeedback
              icon="cloud_off"
              message={getHistoryErrorMessage(historyQuery.error)}
              onRetry={() => void historyQuery.refetch()}
            />
          ) : historyQuery.items.length === 0 ? (
            <HistoryFeedback icon="inbox" message="배송 기록이 없습니다." />
          ) : (
            <>
            <div className={`flex flex-col gap-3 ${historyQuery.isFetching ? 'opacity-60' : ''}`}>
              {historyQuery.items.map((item, index) => (
                <button
                  type="button"
                  key={`${item.deliveryId ?? 'delivery'}-${item.completedAt ?? index}`}
                  onClick={() =>
                    item.deliveryId &&
                    void router.navigate({
                      to: '/rider/history/$deliveryId',
                      params: { deliveryId: String(item.deliveryId) },
                    })
                  }
                  disabled={!item.deliveryId}
                  className="group flex w-full flex-col gap-4 rounded-2xl border border-surface-container bg-surface-container-lowest p-4 text-left shadow-[0_1px_2px_rgba(0,0,0,0.04)] transition-[background-color,border-color,box-shadow,transform] hover:-translate-y-px hover:border-outline-variant hover:bg-surface-container-low hover:shadow-[0_6px_16px_rgba(0,0,0,0.08)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:hover:translate-y-0"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex items-center gap-2 text-secondary">
                      <span className="material-symbols-outlined text-lg" aria-hidden="true">
                        calendar_today
                      </span>
                      <time className="font-label-md">
                      {formatHistoryDate(item.completedAt)} {formatHistoryTime(item.completedAt)}
                      </time>
                    </div>
                    {item.deliveryId && (
                      <span className="shrink-0 font-label-sm text-outline">주문 #{item.deliveryId}</span>
                    )}
                  </div>

                  <div className="relative flex flex-col gap-4 before:absolute before:bottom-5 before:left-[11px] before:top-5 before:w-px before:bg-outline-variant">
                    <div className="relative flex min-w-0 items-start gap-3">
                      <span className="z-10 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-surface-container-lowest text-secondary">
                        <span className="material-symbols-outlined text-base" aria-hidden="true">
                          trip_origin
                        </span>
                      </span>
                      <span className="min-w-0 pt-0.5">
                        <span className="mb-0.5 block font-label-sm text-secondary">픽업지</span>
                        <span className="block truncate font-body-md text-on-surface">
                          {item.pickupRoadAddress ?? '출발지 미제공'}
                        </span>
                      </span>
                    </div>
                    <div className="relative flex min-w-0 items-start gap-3">
                      <span className="z-10 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary-container text-primary">
                        <span className="material-symbols-outlined text-base" aria-hidden="true">
                          location_on
                        </span>
                      </span>
                      <span className="min-w-0 pt-0.5">
                        <span className="mb-0.5 block font-label-sm text-primary">배송지</span>
                        <span className="block truncate font-body-md text-on-surface">
                          {item.destinationRoadAddress ?? '도착지 미제공'}
                        </span>
                      </span>
                    </div>
                  </div>

                  {(item.itemType || item.straightDistanceMeters != null) && (
                    <div className="flex items-center gap-2 border-t border-surface-container pt-3 font-label-md text-secondary">
                      <span className="material-symbols-outlined text-base" aria-hidden="true">
                        inventory_2
                      </span>
                      {item.itemType && <span>{formatItemType(item.itemType)}</span>}
                      {item.itemType && item.straightDistanceMeters != null && (
                        <span aria-hidden="true">·</span>
                      )}
                      {item.straightDistanceMeters != null && (
                        <span>{formatDistance(item.straightDistanceMeters)} 이동</span>
                      )}
                      <span className="material-symbols-outlined ml-auto text-lg text-outline transition-transform group-hover:translate-x-0.5" aria-hidden="true">
                        chevron_right
                      </span>
                    </div>
                  )}
                </button>
              ))}
            </div>

            <nav aria-label="페이지 이동" className="mt-lg flex items-center justify-center gap-4 rounded-xl bg-surface-container-low px-4 py-2">
              <PageButton
                label="이전 페이지"
                icon="chevron_left"
                disabled={!historyQuery.hasPrevious || historyQuery.isFetching}
                onClick={() => setPage((current) => Math.max(0, current - 1))}
              />
              <span aria-live="polite" className="min-w-24 text-center font-label-md text-secondary">
                {historyQuery.page + 1} / {historyQuery.totalPages}
              </span>
              <PageButton
                label="다음 페이지"
                icon="chevron_right"
                disabled={!historyQuery.hasNext || historyQuery.isFetching}
                onClick={() => setPage((current) => current + 1)}
              />
            </nav>
            </>
          )}
        </section>
      </div>
    </main>
  )
}

function PageButton({
  label,
  icon,
  disabled,
  onClick,
}: {
  label: string
  icon: string
  disabled: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="flex h-9 w-9 items-center justify-center rounded-full text-on-surface-variant transition-colors hover:bg-surface-container disabled:cursor-default disabled:text-outline disabled:hover:bg-transparent"
    >
      <span className="material-symbols-outlined" aria-hidden="true">
        {icon}
      </span>
    </button>
  )
}

function HistoryFeedback({
  icon,
  message,
  spin = false,
  onRetry,
}: {
  icon: string
  message: string
  spin?: boolean
  onRetry?: () => void
}) {
  return (
    <div className="flex min-h-64 flex-col items-center justify-center gap-3 rounded-xl bg-surface-container-low p-6 text-center">
      <span
        className={`material-symbols-outlined text-4xl text-secondary ${spin ? 'animate-spin' : ''}`}
        aria-hidden="true"
      >
        {icon}
      </span>
      <p role={onRetry ? 'alert' : 'status'} className="font-body-md text-secondary">
        {message}
      </p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="rounded-xl bg-primary-container px-5 py-2.5 font-label-lg text-on-primary-container"
        >
          다시 시도
        </button>
      )}
    </div>
  )
}
