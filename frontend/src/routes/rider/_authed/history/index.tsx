import { useState } from 'react'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import { useGetRiderSettlements } from '@/api/generated/rider-point/rider-point'
import { formatSettlementAmount, formatSettlementDate, getSettlementErrorMessage } from './-riderHistory'

const PAGE_SIZE = 10

export const Route = createFileRoute('/rider/_authed/history/')({
  component: RiderHistory,
})

function RiderHistory() {
  const router = useRouter()
  const [page, setPage] = useState(0)
  const settlementsQuery = useGetRiderSettlements(
    { page, size: PAGE_SIZE },
    { query: { retry: false, placeholderData: previous => previous } },
  )
  const history = settlementsQuery.data?.data
  const items = history?.items ?? []
  const totalElements = history?.totalElements ?? 0
  const hasPrevious = page > 0
  const hasNext = (page + 1) * PAGE_SIZE < totalElements

  return (
    <main aria-label="배송 이력" className="min-h-screen bg-background pb-[80px] text-on-background">
      <header className="sticky top-0 z-10 flex h-14 w-full items-center bg-surface px-container-margin text-primary">
        <button
          type="button"
          aria-label="라이더 홈으로 돌아가기"
          onClick={() => void router.navigate({ to: '/rider' })}
          className="flex items-center justify-center rounded-full p-2 text-on-surface-variant transition-all duration-200 hover:bg-surface-container-high active:scale-95"
        >
          <span className="material-symbols-outlined" aria-hidden="true">arrow_back</span>
        </button>
        <h1 className="flex-1 text-center font-headline-md text-headline-md font-bold text-on-surface">배송 이력</h1>
        <div className="w-10" aria-hidden="true" />
      </header>

      <div className="flex flex-col gap-md px-container-margin py-md">
        <section aria-label="정산 요약" className="flex items-center justify-between rounded-xl border border-surface-container bg-surface-container-lowest p-md shadow-[0_4px_12px_rgba(0,0,0,0.08)]">
          <div className="flex flex-col">
            <span className="font-label-sm text-label-sm text-secondary">조회된 정산 합계</span>
            <span className="font-headline-lg text-headline-lg text-on-surface">{formatSettlementAmount(history?.totalAmount)}</span>
            <span className="mt-1 font-body-sm text-body-sm text-secondary">총 {totalElements.toLocaleString('ko-KR')}건</span>
          </div>
          <div className="flex items-center justify-center rounded-full bg-primary-container p-3 text-on-primary-container">
            <span className="material-symbols-outlined text-3xl" aria-hidden="true">account_balance_wallet</span>
          </div>
        </section>

        {settlementsQuery.isPending ? (
          <HistoryFeedback icon="progress_activity" message="배송 이력을 불러오고 있어요." spin />
        ) : settlementsQuery.isError ? (
          <HistoryFeedback
            icon="cloud_off"
            message={getSettlementErrorMessage(settlementsQuery.error)}
            onRetry={() => void settlementsQuery.refetch()}
          />
        ) : items.length === 0 ? (
          <HistoryFeedback icon="inbox" message="아직 완료된 배송 이력이 없습니다." />
        ) : (
          <section aria-label="완료 배송 목록" className={`flex flex-col gap-gutter ${settlementsQuery.isFetching ? 'opacity-60' : ''}`}>
            {items.map((item, index) => (
              <article
                key={item.settlementId ?? `${item.deliveryId}-${item.settledAt}-${index}`}
                className="relative flex flex-col gap-sm overflow-hidden rounded-xl border border-surface-container bg-surface-container-lowest p-md"
              >
                <div className="flex w-full items-start justify-between gap-3">
                  <time className="font-label-md text-label-md text-secondary">{formatSettlementDate(item.settledAt)}</time>
                  <span className="shrink-0 rounded-full bg-surface-container-high px-3 py-1 font-label-sm text-label-sm text-primary">정산 완료</span>
                </div>
                <div className="mt-2 flex items-center gap-2">
                  <span className="material-symbols-outlined text-secondary" aria-hidden="true">local_shipping</span>
                  <span className="font-body-md text-body-md text-on-surface">
                    배송 {item.deliveryId ? `#${item.deliveryId}` : '번호 미제공'}
                  </span>
                </div>
                <div className="mt-2 flex items-end justify-between border-t border-surface-container pt-3">
                  <span className="font-label-md text-label-md text-secondary">배송 완료 정산</span>
                  <strong className="font-headline-sm text-headline-sm text-on-surface">{formatSettlementAmount(item.settlementAmount)}</strong>
                </div>
                <div className="absolute inset-y-0 left-0 w-1 bg-primary-container" />
              </article>
            ))}
          </section>
        )}

        {!settlementsQuery.isError && totalElements > PAGE_SIZE && (
          <nav aria-label="배송 이력 페이지" className="mt-2 flex items-center justify-between gap-3">
            <button
              type="button"
              disabled={!hasPrevious || settlementsQuery.isFetching}
              onClick={() => setPage(current => Math.max(0, current - 1))}
              className="flex min-h-12 flex-1 items-center justify-center gap-1 rounded-xl border border-surface-container bg-surface-container-lowest font-label-lg text-secondary disabled:cursor-not-allowed disabled:opacity-40"
            >
              <span className="material-symbols-outlined text-sm" aria-hidden="true">chevron_left</span>
              이전
            </button>
            <span className="min-w-16 text-center font-label-md text-secondary">{page + 1} 페이지</span>
            <button
              type="button"
              disabled={!hasNext || settlementsQuery.isFetching}
              onClick={() => setPage(current => current + 1)}
              className="flex min-h-12 flex-1 items-center justify-center gap-1 rounded-xl border border-surface-container bg-surface-container-lowest font-label-lg text-secondary disabled:cursor-not-allowed disabled:opacity-40"
            >
              다음
              <span className="material-symbols-outlined text-sm" aria-hidden="true">chevron_right</span>
            </button>
          </nav>
        )}
      </div>
    </main>
  )
}

function HistoryFeedback({ icon, message, spin = false, onRetry }: { icon: string; message: string; spin?: boolean; onRetry?: () => void }) {
  return (
    <section className="flex min-h-64 flex-col items-center justify-center gap-3 rounded-xl bg-surface-container-lowest p-6 text-center">
      <span className={`material-symbols-outlined text-4xl text-secondary ${spin ? 'animate-spin' : ''}`} aria-hidden="true">{icon}</span>
      <p role={onRetry ? 'alert' : 'status'} className="font-body-md text-body-md text-secondary">{message}</p>
      {onRetry && (
        <button type="button" onClick={onRetry} className="rounded-xl bg-primary-container px-5 py-2.5 font-label-lg text-on-primary-container">다시 시도</button>
      )}
    </section>
  )
}
