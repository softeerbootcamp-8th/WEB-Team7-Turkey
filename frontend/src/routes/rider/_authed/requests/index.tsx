import { useEffect, useState } from 'react'
import { Capacitor } from '@capacitor/core'
import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import { isAxiosError } from 'axios'
import type { RiderDeliveryRequestSummaryResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { useGetRiderDeliveryRequests } from '@/api/generated/rider-request/rider-request'
import {
  getGetRiderOperatingStatusQueryKey,
  useChangeRiderOperatingStatus,
} from '@/api/generated/rider-operating-status/rider-operating-status'
import { getGetRiderSessionQueryKey } from '@/api/generated/rider-session/rider-session'
import type { ItemFilter, RequestCursor, RiderPosition } from './-requestList'
import {
  buildNextRequestCursor,
  DEFAULT_RADIUS_METERS,
  getPositionUnavailableGuidance,
  getPositionUnavailableSummary,
  getRequestListErrorMessage,
  ITEM_FILTER_OPTIONS,
  RADIUS_OPTIONS,
  requestRiderPosition,
} from './-requestList'
import { RequestCard } from './-components/RequestCard'

type PositionState = { status: 'loading' } | ({ status: 'resolved' } & RiderPosition)

export const Route = createFileRoute('/rider/_authed/requests/')({
  component: RiderRequests,
})

function RiderRequests() {
  const router = useRouter()
  const queryClient = useQueryClient()
  const [radiusMeters, setRadiusMeters] = useState(DEFAULT_RADIUS_METERS)
  const [itemFilter, setItemFilter] = useState<ItemFilter>('ALL')
  const [statusError, setStatusError] = useState<string | null>(null)
  const [position, setPosition] = useState<PositionState>({ status: 'loading' })
  const [showPositionDetail, setShowPositionDetail] = useState(false)
  const [cursor, setCursor] = useState<RequestCursor | undefined>(undefined)
  const [items, setItems] = useState<RiderDeliveryRequestSummaryResponse[]>([])
  const [hasNext, setHasNext] = useState(false)
  const [hasLoadedOnce, setHasLoadedOnce] = useState(false)
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null)

  // 화면 진입 시 1회만 조회한다(#496). "새로 고침" 버튼은 이 좌표를 재사용 — 매번 GPS를 다시
  // 잡지 않는다. 권한 거부·타임아웃이어도 requestRiderPosition이 빈 값으로 resolve하므로
  // 아래 쿼리는 좌표 없이 진행되고, 백엔드가 반경 필터 없이 전체 목록으로 처리한다.
  useEffect(() => {
    let cancelled = false
    requestRiderPosition(navigator.geolocation).then((result) => {
      if (!cancelled) {
        setPosition({ status: 'resolved', ...result })
      }
    })
    return () => {
      cancelled = true
    }
  }, [])

  // 좌표 없이 폴백 중임을 라이더가 알 수 있어야 한다 — 권한 거부·시스템 위치 설정 문제는
  // 화면만 봐서는 구분이 안 되고, 라이더 입장에서 "왜 안 가까운 순인지" 알 방법이 없었다.
  const positionUnavailable = position.status === 'resolved' && position.latitude == null

  const latitude = position.status === 'resolved' ? position.latitude : undefined
  const longitude = position.status === 'resolved' ? position.longitude : undefined
  const hasPosition = latitude != null && longitude != null
  const itemTypeParam = itemFilter === 'ALL' ? undefined : itemFilter

  // 반경·좌표·물품 종류가 바뀌면 이전 목록은 그 조건 기준이 아니므로 커서·누적 목록을 버리고
  // 첫 페이지부터 다시 쌓는다(#509/#522). itemType은 #522부터 서버 요청 파라미터라(이전에는
  // 화면에서만 거르는 값이라 재조회가 필요 없었다) 다른 서버 필터와 똑같이 취급한다.
  useEffect(() => {
    setCursor(undefined)
    setItems([])
    setHasNext(false)
    setHasLoadedOnce(false)
    setLoadMoreError(null)
  }, [radiusMeters, latitude, longitude, itemTypeParam])

  function retryPosition() {
    setPosition({ status: 'loading' })
    setShowPositionDetail(false)
    requestRiderPosition(navigator.geolocation).then((result) => {
      setPosition({ status: 'resolved', ...result })
    })
  }

  const requestsQuery = useGetRiderDeliveryRequests(
    {
      latitude,
      longitude,
      radiusMeters,
      sort: 'DISTANCE',
      itemType: itemTypeParam,
      ...cursor,
    },
    { query: { retry: false, enabled: position.status === 'resolved' } },
  )
  const isInitialLoading = position.status === 'loading' || (!hasLoadedOnce && requestsQuery.isLoading)
  const operatingStatusMutation = useChangeRiderOperatingStatus()

  // 응답 페이지를 로컬 state에 쌓는다(#509) — 커서가 없던(첫 페이지) 요청이면 통째로 교체하고,
  // 커서가 있던(다음 페이지) 요청이면 이어붙인다. Orval이 useInfiniteQuery를 생성하지 않아
  // (react-query 모드가 단일 useQuery) 페이지 병합은 화면에서 직접 관리해야 한다.
  //
  // 의존성을 requestsQuery.data가 아니라 dataUpdatedAt으로 두는 이유(#543): react-query는 기본으로
  // structural sharing을 켜둬서, 새로 받은 응답이 이전 응답과 내용이 완전히 같으면(예: 새로고침해도
  // 서버가 똑같은 목록을 돌려줄 때) data 참조 자체를 재사용한다. data를 의존성으로 쓰면 그럴 때
  // 이펙트가 다시 안 돌아 setItems가 호출 안 되고, refreshList가 미리 비워 둔 배열이 그대로 남아
  // "데이터가 있는데 0건으로 보이는" 버그가 났다(데모 부스에서 실측). dataUpdatedAt은 응답 내용이
  // 같아도 fetch가 끝날 때마다 새 값으로 갱신되므로 이 문제가 없다.
  useEffect(() => {
    if (!requestsQuery.isSuccess || !requestsQuery.data) {
      return
    }
    const page = requestsQuery.data.data ?? {}
    const pageItems = page.items ?? []
    setItems((prev) => (cursor === undefined ? pageItems : [...prev, ...pageItems]))
    setHasNext(page.hasNext ?? false)
    setHasLoadedOnce(true)
    setLoadMoreError(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestsQuery.dataUpdatedAt, requestsQuery.isSuccess])

  // 위와 같은 이유로 error가 아니라 errorUpdatedAt에 의존한다 — 같은 에러가 반복돼도 매번 갱신된다.
  useEffect(() => {
    if (requestsQuery.isError && cursor !== undefined) {
      setLoadMoreError(getRequestListErrorMessage(requestsQuery.error))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestsQuery.isError, requestsQuery.errorUpdatedAt])

  function refreshList() {
    // 더보기 등 다른 요청이 이미 진행 중이면 무시한다 — 중복 요청 방지(CLAUDE.md 동시 요청 고려).
    if (requestsQuery.isFetching) {
      return
    }
    setItems([])
    setHasNext(false)
    setHasLoadedOnce(false)
    setLoadMoreError(null)
    if (cursor === undefined) {
      void requestsQuery.refetch()
    } else {
      setCursor(undefined)
    }
  }

  function loadMoreRequests() {
    if (!hasNext || requestsQuery.isFetching) {
      return
    }
    const nextCursor = buildNextRequestCursor(hasPosition, items[items.length - 1])
    if (!nextCursor) {
      return
    }
    setLoadMoreError(null)
    setCursor(nextCursor)
  }

  const isLoadingMore = requestsQuery.isFetching && cursor !== undefined
  const isRefreshing = requestsQuery.isFetching && cursor === undefined && hasLoadedOnce

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
        onSuccess: async (response) => {
          queryClient.setQueryData(getGetRiderOperatingStatusQueryKey(), response)
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
          <div className="flex min-w-0 items-start gap-3">
            <button
              type="button"
              aria-label="라이더 홈으로 돌아가기"
              onClick={() => void router.navigate({ to: '/rider' })}
              className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-on-surface transition-colors hover:bg-surface-container-low"
            >
              <span className="material-symbols-outlined">arrow_back</span>
            </button>
            <div className="min-w-0">
              <div className="mb-1 flex items-center gap-2">
                <span className="h-2.5 w-2.5 rounded-full bg-tertiary" aria-hidden="true" />
                <span className="text-label-md font-bold text-tertiary">운행 중</span>
              </div>
              <h1 className="text-headline-lg font-bold tracking-tight">주변 배차 콜</h1>
              <p className="mt-1 text-body-md text-secondary">가까운 픽업지부터 보여드려요.</p>
            </div>
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

        {positionUnavailable && (
          <div aria-live="polite" className="mx-5 mt-4 rounded-xl bg-tertiary-container px-4 py-3 text-on-tertiary-container">
            <div className="flex items-start gap-3">
              <button
                type="button"
                title={getPositionUnavailableGuidance(Capacitor.getPlatform())}
                aria-expanded={showPositionDetail}
                aria-controls="position-unavailable-detail"
                aria-label="정확한 원인이 아닐 수 있어요 — 자세히 보기"
                onClick={() => setShowPositionDetail((prev) => !prev)}
                className="shrink-0"
              >
                <span className="material-symbols-outlined text-xl">info</span>
              </button>
              <p className="flex-1 text-body-md font-semibold">
                {getPositionUnavailableSummary(position.status === 'resolved' ? position.unavailableReason : undefined)}
              </p>
              <button
                type="button"
                onClick={retryPosition}
                className="shrink-0 rounded-lg bg-on-tertiary-container px-3 py-1.5 text-label-sm font-bold text-tertiary-container"
              >
                다시 시도
              </button>
            </div>
            {showPositionDetail && (
              <p id="position-unavailable-detail" className="mt-2 pl-8 text-body-md">
                {getPositionUnavailableGuidance(Capacitor.getPlatform())}
              </p>
            )}
          </div>
        )}

        <div className="flex items-center justify-between px-5 py-3 text-body-md">
          <p className="font-bold">
            배차 가능 <span className="text-tertiary">{items.length}건</span>
          </p>
          {isRefreshing && (
            <span aria-live="polite" className="text-label-sm text-secondary">업데이트 중…</span>
          )}
        </div>

        {isInitialLoading && (
          <div aria-live="polite" className="flex flex-1 flex-col items-center justify-center gap-3 px-5 py-20 text-center">
            <span className="material-symbols-outlined animate-spin text-3xl text-tertiary">progress_activity</span>
            <p className="text-body-md text-secondary">주변 콜을 불러오고 있어요.</p>
          </div>
        )}

        {requestsQuery.isError && cursor === undefined && !hasLoadedOnce && (
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

        {hasLoadedOnce && items.length === 0 && (
          <div className="flex flex-1 flex-col items-center justify-center gap-3 px-6 py-20 text-center">
            <div className="flex h-14 w-14 items-center justify-center rounded-full bg-surface-container-high">
              <span className="material-symbols-outlined text-3xl text-outline">two_wheeler</span>
            </div>
            <div>
              <p className="text-body-lg font-bold">현재 수행 가능한 콜이 없습니다.</p>
              <p className="mt-1 text-body-md text-secondary">
                {itemFilter !== 'ALL' ? '다른 물품 크기를 선택해 보세요.' : '잠시 후 새로 고침해 주세요.'}
              </p>
            </div>
          </div>
        )}

        {hasLoadedOnce && items.length > 0 && (
          <section aria-label="배차 가능한 콜" className="border-y border-outline-variant bg-surface-container-lowest">
            {items.map((request, index) => (
              <RequestCard
                key={request.deliveryId ?? `${request.requestedAt ?? 'request'}-${index}`}
                request={request}
                onSelect={openRequest}
              />
            ))}
          </section>
        )}

        {hasLoadedOnce && hasNext && (
          <div className="px-5 py-4">
            {loadMoreError && (
              <p role="alert" className="mb-3 text-center text-body-md text-error">{loadMoreError}</p>
            )}
            <button
              type="button"
              onClick={loadMoreRequests}
              disabled={isLoadingMore}
              className="flex h-11 w-full items-center justify-center gap-2 rounded-xl border border-outline-variant bg-surface-container-lowest text-label-lg font-bold text-on-surface transition-colors hover:bg-surface-container-low disabled:opacity-60"
            >
              {isLoadingMore && <span className="material-symbols-outlined animate-spin text-xl">progress_activity</span>}
              {isLoadingMore ? '불러오는 중…' : '더보기'}
            </button>
          </div>
        )}
      </main>

      <nav aria-label="목록 동작" className="sticky bottom-0 z-30 flex h-20 w-full shrink-0 items-center border-t border-outline-variant bg-surface-container-lowest px-5">
        <button
          type="button"
          onClick={refreshList}
          disabled={requestsQuery.isFetching || isInitialLoading}
          className="flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-primary-container text-label-lg font-bold text-on-primary-container transition-colors hover:bg-primary-fixed disabled:opacity-60"
        >
          <span className={`material-symbols-outlined text-xl ${isRefreshing ? 'animate-spin' : ''}`}>refresh</span>
          {isRefreshing ? '새로 고치는 중…' : '새로 고침'}
        </button>
      </nav>
    </div>
  )
}
