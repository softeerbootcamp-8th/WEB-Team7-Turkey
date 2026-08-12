import { useState } from 'react'
import { useGetCustomerPointTransactions } from '@/api/generated/customer-point/customer-point'
import type { GetCustomerPointTransactionsType } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import {
  formatCustomerPoints,
  formatCustomerPointTransactionDate,
  getCustomerPointErrorMessage,
  getCustomerPointTransactionLabel,
  getCustomerPointTransactionReference,
  getSignedCustomerPointAmount,
} from '../-customerPoints'

const PAGE_SIZE = 20

type PointFilter = 'ALL' | GetCustomerPointTransactionsType

const filterOptions: { value: PointFilter; label: string }[] = [
  { value: 'ALL', label: '전체' },
  { value: 'CHARGE', label: '충전' },
  { value: 'ORDER_USE', label: '사용' },
  { value: 'ORDER_REFUND', label: '환급' },
  { value: 'CHARGE_REFUND', label: '충전 취소' },
]

export function PointHistoryTable() {
  const [page, setPage] = useState(0)
  const [filter, setFilter] = useState<PointFilter>('ALL')
  const type = filter === 'ALL' ? undefined : filter
  const transactionsQuery = useGetCustomerPointTransactions(
    { type, page, size: PAGE_SIZE },
    {
      query: {
        retry: false,
        refetchOnMount: 'always',
        placeholderData: (previous) => previous,
      },
    },
  )
  const response = transactionsQuery.data?.data
  const items = response?.items ?? []
  const totalElements = response?.totalElements ?? 0
  const hasInvalidResponse = transactionsQuery.isSuccess && response == null
  const hasPrevious = page > 0
  const hasNext = (page + 1) * PAGE_SIZE < totalElements

  return (
    <section aria-labelledby="point-history-title" className="bg-surface-container-lowest">
      <div className="flex items-center justify-between gap-4 border-b border-outline-variant px-5 py-4">
        <div>
          <h2 id="point-history-title" className="text-headline-sm font-bold text-on-surface">
            이용 내역
          </h2>
          {!transactionsQuery.isPending && !transactionsQuery.isError && !hasInvalidResponse && (
            <p className="mt-1 text-label-md text-secondary">총 {totalElements.toLocaleString('ko-KR')}건</p>
          )}
        </div>
        <label className="relative">
          <span className="sr-only">거래 유형</span>
          <select
            value={filter}
            onChange={(event) => {
              setFilter(event.target.value as PointFilter)
              setPage(0)
            }}
            className="min-h-10 appearance-none rounded-xl border border-outline-variant bg-surface-container-lowest py-2 pl-3 pr-9 text-body-md text-on-surface focus:outline-none focus:ring-2 focus:ring-primary-container"
          >
            {filterOptions.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <span
            aria-hidden="true"
            className="material-symbols-outlined pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-lg text-secondary"
          >
            expand_more
          </span>
        </label>
      </div>

      {transactionsQuery.isPending ? (
        <HistoryFeedback icon="progress_activity" message="포인트 내역을 불러오고 있어요." spin />
      ) : transactionsQuery.isError || hasInvalidResponse ? (
        <HistoryFeedback
          alert
          icon="cloud_off"
          message={
            transactionsQuery.isError
              ? getCustomerPointErrorMessage(
                  transactionsQuery.error,
                  '포인트 내역을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
                )
              : '포인트 내역을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
          }
          onRetry={() => void transactionsQuery.refetch()}
        />
      ) : items.length === 0 ? (
        <HistoryFeedback icon="receipt_long" message="아직 포인트 이용 내역이 없습니다." />
      ) : (
        <>
          {transactionsQuery.isFetching && (
            <p aria-live="polite" className="px-5 pt-3 text-right text-label-sm text-secondary">
              업데이트 중…
            </p>
          )}
          <ul
            className={`divide-y divide-outline-variant px-5 transition-opacity ${transactionsQuery.isFetching ? 'opacity-60' : ''}`}
          >
            {items.map((item, index) => {
              const signedAmount = getSignedCustomerPointAmount(item)
              const reference = getCustomerPointTransactionReference(item)
              return (
                <li
                  key={item.transactionId ?? `${item.createdAt ?? 'point'}-${index}`}
                  className="flex items-center justify-between gap-4 py-4"
                >
                  <div className="min-w-0">
                    <p className="text-label-lg font-bold text-on-surface">
                      {getCustomerPointTransactionLabel(item.transactionType)}
                    </p>
                    <p className="mt-1 flex flex-wrap items-center gap-x-2 text-label-md text-secondary">
                      <time>{formatCustomerPointTransactionDate(item.createdAt)}</time>
                      {reference && <span aria-label={`연관 항목 ${reference}`}>· {reference}</span>}
                    </p>
                  </div>
                  <div className="shrink-0 text-right">
                    <p className={`text-label-lg font-bold ${signedAmount > 0 ? 'text-tertiary' : 'text-on-surface'}`}>
                      {signedAmount > 0 ? '+' : ''}
                      {formatCustomerPoints(signedAmount)}
                    </p>
                    <p className="mt-1 text-label-sm text-secondary">
                      잔액 {formatCustomerPoints(item.balanceAfter)}
                    </p>
                  </div>
                </li>
              )
            })}
          </ul>

          {totalElements > PAGE_SIZE && (
            <nav aria-label="포인트 이용 내역 페이지" className="flex items-center gap-3 border-t border-outline-variant px-5 py-4">
              <button
                type="button"
                disabled={!hasPrevious || transactionsQuery.isFetching}
                onClick={() => setPage((current) => Math.max(0, current - 1))}
                className="flex min-h-11 flex-1 items-center justify-center gap-1 rounded-xl border border-outline-variant text-label-lg font-bold disabled:opacity-40"
              >
                <span aria-hidden="true" className="material-symbols-outlined text-lg">chevron_left</span>
                이전
              </button>
              <span className="min-w-16 text-center text-label-md text-secondary">{page + 1} 페이지</span>
              <button
                type="button"
                disabled={!hasNext || transactionsQuery.isFetching}
                onClick={() => setPage((current) => current + 1)}
                className="flex min-h-11 flex-1 items-center justify-center gap-1 rounded-xl border border-outline-variant text-label-lg font-bold disabled:opacity-40"
              >
                다음
                <span aria-hidden="true" className="material-symbols-outlined text-lg">chevron_right</span>
              </button>
            </nav>
          )}
        </>
      )}
    </section>
  )
}

function HistoryFeedback({
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
    <div className="flex min-h-64 flex-col items-center justify-center gap-3 px-6 py-12 text-center">
      <span
        aria-hidden="true"
        className={`material-symbols-outlined text-4xl text-outline ${spin ? 'animate-spin' : ''}`}
      >
        {icon}
      </span>
      <p role={alert ? 'alert' : 'status'} className="text-body-md text-secondary">
        {message}
      </p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="rounded-xl bg-primary-container px-5 py-2.5 text-label-lg font-bold text-on-primary-container"
        >
          다시 시도
        </button>
      )}
    </div>
  )
}
