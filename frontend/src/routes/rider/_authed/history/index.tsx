import { useState } from 'react'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import { formatHistoryDay, formatHistoryTime, getHistoryErrorMessage, shiftHistoryDay } from './-riderHistory'
import { useRiderDeliveryHistory } from './-useRiderDeliveryHistory'

export const Route = createFileRoute('/rider/_authed/history/')({
  component: RiderHistory,
})

function RiderHistory() {
  const router = useRouter()
  const [selectedDate, setSelectedDate] = useState(() => new Date())
  const historyQuery = useRiderDeliveryHistory(selectedDate)

  return (
    <main aria-label="배송 기록" className="min-h-screen bg-background pb-20 text-on-background">
      <header className="sticky top-0 z-10 flex h-14 items-center bg-surface px-container-margin">
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
        <section aria-label="조회 날짜" className="bg-surface-container-lowest px-container-margin py-lg">
          <div className="flex items-center justify-center gap-lg">
            <DateButton
              label="이전 날짜"
              icon="chevron_left"
              onClick={() => setSelectedDate((current) => shiftHistoryDay(current, -1))}
            />
            <time
              className="min-w-32 text-center font-headline-sm text-on-surface"
              dateTime={selectedDate.toISOString()}
            >
              {formatHistoryDay(selectedDate)}
            </time>
            <DateButton
              label="다음 날짜"
              icon="chevron_right"
              onClick={() => setSelectedDate((current) => shiftHistoryDay(current, 1))}
            />
          </div>

          <dl className="mt-lg grid grid-cols-2 divide-x divide-surface-container rounded-xl bg-surface-container-low px-md py-4">
            <Summary label="Completed" value={historyQuery.summary.completed} accent="text-tertiary" />
            <Summary label="Canceled" value={historyQuery.summary.canceled} accent="text-error" />
          </dl>
        </section>

        <div className="h-2 bg-surface-container-low" aria-hidden="true" />

        <section aria-label="배송 기록 목록" className="flex flex-col px-container-margin py-lg">
          <h2 className="mb-md font-headline-sm text-on-surface">배송 기록</h2>
          {historyQuery.isPending ? (
            <HistoryFeedback icon="progress_activity" message="배송 기록을 불러오고 있어요." spin />
          ) : historyQuery.isError ? (
            <HistoryFeedback
              icon="cloud_off"
              message={getHistoryErrorMessage(historyQuery.error)}
              onRetry={() => void historyQuery.refetch()}
            />
          ) : historyQuery.items.length === 0 ? (
            <HistoryFeedback icon="inbox" message="이 날짜의 배송 기록이 없습니다." />
          ) : (
            <div className={`flex flex-col gap-gutter ${historyQuery.isFetching ? 'opacity-60' : ''}`}>
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
                  className="flex w-full items-center gap-md rounded-xl border border-surface-container bg-surface-container-lowest p-md text-left transition-colors hover:bg-surface-container-low disabled:cursor-default"
                >
                  <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-primary-container text-on-primary-container">
                    <span className="material-symbols-outlined" aria-hidden="true">
                      local_shipping
                    </span>
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block font-label-lg text-on-surface">
                      배송 {item.deliveryId ? `#${item.deliveryId}` : '번호 미제공'}
                    </span>
                    <time className="mt-1 block font-body-md text-secondary">
                      {formatHistoryTime(item.completedAt)} 완료
                    </time>
                  </span>
                  <span className="rounded-full bg-tertiary-container px-3 py-1 font-label-sm text-on-tertiary-container">
                    Completed
                  </span>
                  {item.deliveryId && (
                    <span className="material-symbols-outlined text-lg text-secondary" aria-hidden="true">
                      chevron_right
                    </span>
                  )}
                </button>
              ))}
            </div>
          )}
        </section>
      </div>
    </main>
  )
}

function DateButton({ label, icon, onClick }: { label: string; icon: string; onClick: () => void }) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className="flex h-9 w-9 items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container"
    >
      <span className="material-symbols-outlined" aria-hidden="true">
        {icon}
      </span>
    </button>
  )
}

function Summary({ label, value, accent }: { label: string; value: number; accent: string }) {
  return (
    <div className="flex flex-col items-center gap-1">
      <dt className="font-label-md text-secondary">{label}</dt>
      <dd className={`font-headline-lg ${accent}`}>{value}</dd>
    </div>
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
