import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import { isAxiosError } from 'axios'
import { useGetDeliveryRequests } from '@/api/generated/rider-request/rider-request'
import { useChangeOperatingStatus } from '@/api/generated/rider-operating-status/rider-operating-status'
import { getGetRiderSessionQueryKey } from '@/api/generated/rider-session/rider-session'
import type { ItemFilter } from './-requestList'
import {
  DEFAULT_RADIUS_METERS,
  filterRequestsByItem,
  getRequestListErrorMessage,
  ITEM_FILTER_OPTIONS,
  RADIUS_OPTIONS,
} from './-requestList'
import { RequestCard } from './-components/RequestCard'

export const Route = createFileRoute('/rider/_authed/requests/')({
  component: RiderRequests,
})

function RiderRequests() {
  const router = useRouter()
  const queryClient = useQueryClient()
  const [radiusMeters, setRadiusMeters] = useState(DEFAULT_RADIUS_METERS)
  const [itemFilter, setItemFilter] = useState<ItemFilter>('ALL')
  const [statusError, setStatusError] = useState<string | null>(null)

  const requestsQuery = useGetDeliveryRequests(
    { radiusMeters, sort: 'DISTANCE' },
    { query: { retry: false } },
  )
  const operatingStatusMutation = useChangeOperatingStatus()

  const requests = requestsQuery.data?.data ?? []
  const visibleRequests = filterRequestsByItem(requests, itemFilter)

  function openRequest(deliveryId: number) {
    void router.navigate({
      to: '/rider/requests/$deliveryId',
      params: { deliveryId: String(deliveryId) },
    })
  }

  function stopOperating() {
    if (operatingStatusMutation.isPending || !window.confirm('운행을 종료할까요?')) {
      return
    }

    setStatusError(null)
    operatingStatusMutation.mutate(
      { data: { action: 'GO_OFFLINE' } },
      {
        onSuccess: async () => {
          queryClient.removeQueries({ queryKey: getGetRiderSessionQueryKey() })
          await router.navigate({ to: '/rider' })
        },
        onError: async (error) => {
          if (isAxiosError(error) && error.response?.status === 409) {
            queryClient.removeQueries({ queryKey: getGetRiderSessionQueryKey() })
            await router.navigate({ to: '/rider/delivery' })
            return
          }
          setStatusError('운행을 종료하지 못했습니다. 잠시 후 다시 시도해 주세요.')
        },
      },
    )
  }

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-[604px] flex-col bg-surface font-sans text-on-surface shadow-sm">
      <header className="sticky top-0 z-20 border-b border-outline-variant bg-surface-container-lowest px-5 pb-4 pt-5">
        <div className="flex items-start justify-between gap-4">
          <div>
            <div className="mb-1 flex items-center gap-2">
              <span className="h-2.5 w-2.5 rounded-full bg-tertiary" aria-hidden="true" />
              <span className="text-label-md font-bold text-tertiary">운행 중</span>
            </div>
            <h1 className="text-headline-lg font-bold tracking-tight">주변 배차 콜</h1>
            <p className="mt-1 text-body-md text-secondary">가까운 픽업지부터 보여드려요.</p>
          </div>
          <button
            type="button"
            onClick={stopOperating}
            disabled={operatingStatusMutation.isPending}
            className="rounded-xl border border-outline-variant bg-surface-container-lowest px-3.5 py-2.5 text-label-md font-bold text-secondary transition-colors hover:bg-surface-container-low disabled:opacity-50"
          >
            {operatingStatusMutation.isPending ? '종료 중…' : '운행 종료'}
          </button>
        </div>
      </header>

      <main aria-label="배송요청 목록" className="flex flex-1 flex-col">
        <section aria-label="콜 필터" className="border-b border-outline-variant bg-surface-container-lowest px-5 py-4">
          <div className="grid grid-cols-2 gap-3">
            <label className="flex flex-col gap-1.5 text-label-sm font-bold text-secondary">
              물품 크기
              <span className="relative">
                <select
                  value={itemFilter}
                  onChange={(event) => setItemFilter(event.target.value as ItemFilter)}
                  className="h-11 w-full appearance-none rounded-xl border border-outline-variant bg-surface-container-lowest px-3 pr-9 text-body-md font-semibold text-on-surface outline-none focus:border-tertiary focus:ring-2 focus:ring-tertiary-container"
                >
                  {ITEM_FILTER_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
                <span className="material-symbols-outlined pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-xl text-outline">expand_more</span>
              </span>
            </label>

            <label className="flex flex-col gap-1.5 text-label-sm font-bold text-secondary">
              픽업 거리
              <span className="relative">
                <select
                  value={radiusMeters}
                  onChange={(event) => setRadiusMeters(Number(event.target.value))}
                  className="h-11 w-full appearance-none rounded-xl border border-outline-variant bg-surface-container-lowest px-3 pr-9 text-body-md font-semibold text-on-surface outline-none focus:border-tertiary focus:ring-2 focus:ring-tertiary-container"
                >
                  {RADIUS_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>{option.label} 이내</option>
                  ))}
                </select>
                <span className="material-symbols-outlined pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-xl text-outline">expand_more</span>
              </span>
            </label>
          </div>
        </section>

        {statusError && (
          <p role="alert" className="mx-5 mt-4 rounded-xl bg-error-container px-4 py-3 text-body-md text-on-error-container">
            {statusError}
          </p>
        )}

        <div className="flex items-center justify-between px-5 py-3 text-body-md">
          <p className="font-bold">
            배차 가능 <span className="text-tertiary">{visibleRequests.length}건</span>
          </p>
          {requestsQuery.isFetching && !requestsQuery.isLoading && (
            <span aria-live="polite" className="text-label-sm text-secondary">업데이트 중…</span>
          )}
        </div>

        {requestsQuery.isLoading && (
          <div aria-live="polite" className="flex flex-1 flex-col items-center justify-center gap-3 px-5 py-20 text-center">
            <span className="material-symbols-outlined animate-spin text-3xl text-tertiary">progress_activity</span>
            <p className="text-body-md text-secondary">주변 콜을 불러오고 있어요.</p>
          </div>
        )}

        {requestsQuery.isError && (
          <div role="alert" className="mx-5 flex flex-1 flex-col items-center justify-center gap-4 rounded-2xl bg-error-container px-6 py-12 text-center text-on-error-container">
            <span className="material-symbols-outlined text-3xl">cloud_off</span>
            <p className="text-body-md font-semibold">{getRequestListErrorMessage(requestsQuery.error)}</p>
            <button
              type="button"
              onClick={() => void requestsQuery.refetch()}
              className="rounded-xl bg-on-error-container px-4 py-2.5 text-label-md font-bold text-on-error"
            >
              다시 시도
            </button>
          </div>
        )}

        {requestsQuery.isSuccess && visibleRequests.length === 0 && (
          <div className="flex flex-1 flex-col items-center justify-center gap-3 px-6 py-20 text-center">
            <div className="flex h-14 w-14 items-center justify-center rounded-full bg-surface-container-high">
              <span className="material-symbols-outlined text-3xl text-outline">two_wheeler</span>
            </div>
            <div>
              <p className="text-body-lg font-bold">현재 수행 가능한 콜이 없습니다.</p>
              <p className="mt-1 text-body-md text-secondary">
                {requests.length > 0 ? '다른 물품 크기를 선택해 보세요.' : '잠시 후 새로 고침해 주세요.'}
              </p>
            </div>
          </div>
        )}

        {requestsQuery.isSuccess && visibleRequests.length > 0 && (
          <section aria-label="배차 가능한 콜" className="border-y border-outline-variant bg-surface-container-lowest">
            {visibleRequests.map((request, index) => (
              <RequestCard
                key={request.deliveryId ?? `${request.requestedAt ?? 'request'}-${index}`}
                request={request}
                onSelect={openRequest}
              />
            ))}
          </section>
        )}
      </main>

      <nav aria-label="목록 동작" className="sticky bottom-0 z-30 flex h-20 w-full shrink-0 items-center border-t border-outline-variant bg-surface-container-lowest px-5">
        <button
          type="button"
          onClick={() => void requestsQuery.refetch()}
          disabled={requestsQuery.isFetching}
          className="flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-primary-container text-label-lg font-bold text-on-primary-container transition-colors hover:bg-primary-fixed disabled:opacity-60"
        >
          <span className={`material-symbols-outlined text-xl ${requestsQuery.isFetching ? 'animate-spin' : ''}`}>refresh</span>
          {requestsQuery.isFetching ? '새로 고치는 중…' : '새로 고침'}
        </button>
      </nav>
    </div>
  )
}
