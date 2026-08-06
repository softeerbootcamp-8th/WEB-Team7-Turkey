import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import {
  getGetRiderOperatingStatusQueryOptions,
} from '@/api/generated/rider-operating-status/rider-operating-status'
import {
  useAcceptDeliveryRequest,
  useGetDeliveryRequest,
} from '@/api/generated/rider-request/rider-request'
import { getGetRiderSessionQueryKey } from '@/api/generated/rider-session/rider-session'
import { RequestRouteMap } from './-components/RequestRouteMap'
import {
  classifyAcceptFailure,
  formatDistance,
  formatItemType,
  formatMinutes,
  formatMoney,
  formatRequestedAt,
  getAcceptErrorMessage,
  getDetailErrorMessage,
} from './-requestDetail'

export const Route = createFileRoute('/rider/_authed/requests/$deliveryId/')({
  component: RiderRequestDetail,
})

function RiderRequestDetail() {
  const { deliveryId: deliveryIdParam } = Route.useParams()
  const router = useRouter()
  const queryClient = useQueryClient()
  const [acceptError, setAcceptError] = useState<string | null>(null)
  const [competitionLost, setCompetitionLost] = useState(false)
  const [checkingResult, setCheckingResult] = useState(false)

  // 손잡이를 드래그해 정보 시트를 내리고 지도를 넓게 볼 수 있게 한다. 놓으면 접힘/펼침 중
  // 가까운 쪽으로 스냅한다. 지도는 펼침 크기로 고정 렌더하고, 이 값(px)은 "보이는 지도 높이"
  // = 시트의 top 위치다 — 시트가 그 아래를 덮으므로 값이 커질수록 지도가 더 드러난다.
  const [viewportHeight, setViewportHeight] = useState(() =>
    typeof window === 'undefined' ? 800 : window.innerHeight,
  )
  const [mapExpanded, setMapExpanded] = useState(false)
  const [dragHeight, setDragHeight] = useState<number | null>(null)
  const dragStartRef = useRef<{ startY: number; startHeight: number } | null>(null)
  const didDragRef = useRef(false)

  useEffect(() => {
    const onResize = () => setViewportHeight(window.innerHeight)
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  const collapsedMapHeight = Math.round(viewportHeight * 0.38)
  const expandedMapHeight = Math.round(viewportHeight * 0.72)
  const clampMapHeight = (value: number) =>
    Math.min(Math.max(value, collapsedMapHeight), expandedMapHeight)
  const mapHeight = dragHeight ?? (mapExpanded ? expandedMapHeight : collapsedMapHeight)

  function onHandlePointerDown(event: ReactPointerEvent<HTMLButtonElement>) {
    event.currentTarget.setPointerCapture(event.pointerId)
    dragStartRef.current = { startY: event.clientY, startHeight: mapHeight }
    didDragRef.current = false
    setDragHeight(mapHeight)
  }

  function onHandlePointerMove(event: ReactPointerEvent<HTMLButtonElement>) {
    const start = dragStartRef.current
    if (!start) {
      return
    }
    const delta = event.clientY - start.startY
    if (Math.abs(delta) > 5) {
      didDragRef.current = true
    }
    setDragHeight(clampMapHeight(start.startHeight + delta))
  }

  function onHandlePointerEnd(event: ReactPointerEvent<HTMLButtonElement>) {
    const start = dragStartRef.current
    if (!start) {
      return
    }
    try {
      event.currentTarget.releasePointerCapture(event.pointerId)
    } catch {
      // 이미 캡처가 해제된 경우는 무시한다.
    }
    if (didDragRef.current) {
      const finalHeight = clampMapHeight(start.startHeight + (event.clientY - start.startY))
      setMapExpanded(finalHeight >= (collapsedMapHeight + expandedMapHeight) / 2)
    } else {
      // 드래그 없이 탭하면 토글.
      setMapExpanded((prev) => !prev)
    }
    setDragHeight(null)
    dragStartRef.current = null
  }

  function onHandleKeyDown(event: ReactKeyboardEvent<HTMLButtonElement>) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      setMapExpanded((prev) => !prev)
    }
  }

  const deliveryId = Number(deliveryIdParam)
  const isValidDeliveryId = Number.isSafeInteger(deliveryId) && deliveryId > 0
  const requestQuery = useGetDeliveryRequest(deliveryId, {
    query: { enabled: isValidDeliveryId, retry: false },
  })
  const acceptMutation = useAcceptDeliveryRequest()

  const request = requestQuery.data?.data
  const isAccepting = acceptMutation.isPending || checkingResult

  function goToList() {
    void router.navigate({ to: '/rider/requests' })
  }

  async function goToDelivery() {
    queryClient.removeQueries({ queryKey: getGetRiderSessionQueryKey() })
    await router.navigate({ to: '/rider/delivery' })
  }

  function acceptRequest() {
    if (!isValidDeliveryId || isAccepting || competitionLost) {
      return
    }

    setAcceptError(null)
    acceptMutation.mutate(
      { deliveryId },
      {
        onSuccess: () => void goToDelivery(),
        onError: async (error) => {
          const failureKind = classifyAcceptFailure(error)

          if (failureKind === 'competition') {
            setCompetitionLost(true)
            return
          }
          if (failureKind === 'failure') {
            setAcceptError(getAcceptErrorMessage(error))
            return
          }

          setCheckingResult(true)
          try {
            const operatingStatus = await queryClient.fetchQuery({
              ...getGetRiderOperatingStatusQueryOptions(),
              staleTime: 0,
            })

            if (operatingStatus.data?.operatingStatus === 'BUSY') {
              await goToDelivery()
              return
            }
            setAcceptError('배차 결과를 확인했지만 진행 중인 배송이 없습니다. 다시 시도해 주세요.')
          } catch {
            setAcceptError('배차 결과를 확인할 수 없습니다. 잠시 후 진행 배송 화면에서 확인해 주세요.')
          } finally {
            setCheckingResult(false)
          }
        },
      },
    )
  }

  if (!isValidDeliveryId) {
    return (
      <DetailMessage
        title="잘못된 콜 번호입니다."
        description="목록에서 다시 콜을 선택해 주세요."
        onBack={goToList}
      />
    )
  }

  if (requestQuery.isLoading) {
    return (
      <main aria-label="배송요청 상세" className="mx-auto flex min-h-screen w-full max-w-[604px] items-center justify-center bg-surface px-5">
        <div aria-live="polite" className="flex flex-col items-center gap-3 text-center">
          <span className="material-symbols-outlined animate-spin text-4xl text-tertiary">progress_activity</span>
          <p className="text-body-md text-secondary">콜 상세 정보를 불러오고 있어요.</p>
        </div>
      </main>
    )
  }

  if (requestQuery.isError || !request) {
    return (
      <DetailMessage
        title={requestQuery.isError ? getDetailErrorMessage(requestQuery.error) : '콜 상세 정보가 없습니다.'}
        description="목록으로 돌아가 다른 콜을 확인해 주세요."
        onBack={goToList}
        onRetry={() => void requestQuery.refetch()}
      />
    )
  }

  const requestedAt = formatRequestedAt(request.requestedAt)
  const pickupAddress = request.pickup?.roadAddress || '픽업 주소 정보 없음'
  const destinationAddress = request.destination?.roadAddress || '도착 주소 정보 없음'
  const fare = request.estimatedFare

  return (
    <main aria-label="배송요청 상세" className="relative mx-auto h-[100dvh] w-full max-w-[604px] overflow-hidden bg-surface font-sans text-on-surface shadow-sm">
      {/* 지도는 펼침 크기로 고정 렌더하고, 정보 시트가 그 위를 덮는다. 시트를 내리면 이미
          그려진 지도가 드러날 뿐이라 축척은 그대로고 리사이즈·relayout 이 없다. */}
      <section
        aria-label="픽업 및 도착 위치"
        className="relative bg-surface-container-high"
        style={{ height: expandedMapHeight }}
      >
        <RequestRouteMap
          pickup={request.pickup}
          destination={request.destination}
          bottomInsetPx={expandedMapHeight - collapsedMapHeight}
        />
        <button
          type="button"
          aria-label="콜 목록으로 돌아가기"
          onClick={goToList}
          className="absolute left-4 top-4 z-10 flex h-12 w-12 items-center justify-center rounded-full bg-surface-container-lowest text-on-surface shadow-md"
        >
          <span className="material-symbols-outlined">arrow_back</span>
        </button>
      </section>

      <section
        className={`absolute inset-x-0 bottom-0 z-10 -mt-5 flex min-h-0 flex-col rounded-t-3xl bg-surface-container-lowest shadow-[0_-4px_12px_rgba(0,0,0,0.06)] ${dragHeight === null ? 'transition-[top] duration-200 ease-out' : ''}`}
        style={{ top: mapHeight }}
      >
        <button
          type="button"
          aria-label={mapExpanded ? '정보 시트 다시 펼치기' : '정보 시트 내리고 지도 넓게 보기'}
          onPointerDown={onHandlePointerDown}
          onPointerMove={onHandlePointerMove}
          onPointerUp={onHandlePointerEnd}
          onPointerCancel={onHandlePointerEnd}
          onKeyDown={onHandleKeyDown}
          className="flex w-full shrink-0 cursor-grab touch-none justify-center pb-3 pt-3 active:cursor-grabbing"
        >
          <span className="h-1.5 w-12 rounded-full bg-outline-variant" aria-hidden="true" />
        </button>

        <div className="flex-1 overflow-y-auto px-5 pb-6">
          <div className="mb-5 flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <span className="rounded-md bg-tertiary-container px-2 py-1 text-label-sm font-bold text-on-tertiary-container">배차 콜</span>
              <span className="text-label-md font-semibold text-secondary">#{request.deliveryId ?? deliveryId}</span>
            </div>
            {requestedAt && <time className="text-label-sm text-secondary">요청 {requestedAt}</time>}
          </div>

          <div className="relative mb-5 ml-1 space-y-7 border-l-2 border-dashed border-outline-variant pl-7">
            <div className="relative">
              <span className="absolute -left-[35px] top-1 h-3 w-3 rounded-full border-2 border-white bg-[#3b82f6] shadow-sm" aria-hidden="true" />
              <p className="text-label-sm font-bold text-[#3b82f6]">픽업</p>
              <p className="mt-1 break-words text-title-md font-bold">{pickupAddress}</p>
            </div>
            <div className="relative">
              <span className="absolute -left-[35px] top-1 h-3 w-3 rounded-full border-2 border-white bg-[#ef4444] shadow-sm" aria-hidden="true" />
              <p className="text-label-sm font-bold text-[#ef4444]">도착</p>
              <p className="mt-1 break-words text-title-md font-bold">{destinationAddress}</p>
            </div>
          </div>

          <dl className="mb-4 grid grid-cols-3 gap-2 rounded-2xl bg-surface-container-low p-4 text-center">
            <InfoItem label="물품" value={formatItemType(request.itemType)} />
            <InfoItem label="이동 거리" value={formatDistance(request.straightDistanceMeters)} />
            <InfoItem label="예상 시간" value={formatMinutes(request.estimatedMinutes)} />
          </dl>

          <section aria-labelledby="settlement-title" className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-5">
            <div className="mb-4 flex items-center justify-between gap-4">
              <h2 id="settlement-title" className="text-title-md font-bold">예상 정산액</h2>
              <strong className="text-headline-sm text-tertiary">{formatMoney(request.expectedSettlementAmount)}</strong>
            </div>
            <dl className="space-y-2 text-body-md text-secondary">
              <FareRow label="기본 운임" amount={fare?.baseFare} />
              <FareRow label="거리 운임" amount={fare?.distanceFare} />
              <FareRow label="물품 할증" amount={fare?.itemSurcharge} />
              <FareRow label="예상 총 운임" amount={fare?.totalFare} emphasized />
            </dl>
          </section>

          {acceptError && (
            <p role="alert" className="mt-4 rounded-xl bg-error-container px-4 py-3 text-body-md font-semibold text-on-error-container">
              {acceptError}
            </p>
          )}
        </div>

        <nav aria-label="콜 상세 동작" className="sticky bottom-0 z-20 grid w-full grid-cols-3 border-t border-outline-variant bg-surface-container-lowest p-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]">
          <button
            type="button"
            onClick={goToList}
            disabled={isAccepting}
            className="h-14 rounded-l-xl bg-surface-container-high text-label-lg font-bold text-secondary disabled:opacity-50"
          >
            넘기기
          </button>
          <button
            type="button"
            onClick={acceptRequest}
            disabled={isAccepting || competitionLost}
            className="col-span-2 h-14 rounded-r-xl bg-primary-container text-label-lg font-bold text-on-primary-container transition-colors hover:bg-primary-fixed disabled:cursor-not-allowed disabled:opacity-60"
          >
            {checkingResult ? '배차 확인 중…' : acceptMutation.isPending ? '수락 처리 중…' : '수락하기'}
          </button>
        </nav>
      </section>

      {competitionLost && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 px-5" role="dialog" aria-modal="true" aria-labelledby="competition-title">
          <div className="w-full max-w-sm rounded-3xl bg-surface-container-lowest p-6 text-center shadow-xl">
            <span className="material-symbols-outlined text-4xl text-secondary">person_check</span>
            <h2 id="competition-title" className="mt-3 text-title-lg font-bold">이미 배차된 콜입니다.</h2>
            <p className="mt-2 text-body-md text-secondary">다른 라이더가 먼저 수락했어요. 목록에서 새 콜을 확인해 주세요.</p>
            <button
              type="button"
              onClick={goToList}
              className="mt-6 h-12 w-full rounded-xl bg-primary-container text-label-lg font-bold text-on-primary-container"
            >
              콜 목록으로 돌아가기
            </button>
          </div>
        </div>
      )}
    </main>
  )
}

function InfoItem({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-label-sm text-secondary">{label}</dt>
      <dd className="mt-1 break-words text-body-md font-bold text-on-surface">{value}</dd>
    </div>
  )
}

function FareRow({ label, amount, emphasized = false }: { label: string; amount: number | undefined; emphasized?: boolean }) {
  return (
    <div className={`flex justify-between gap-4 ${emphasized ? 'border-t border-outline-variant pt-2 font-bold text-on-surface' : ''}`}>
      <dt>{label}</dt>
      <dd>{formatMoney(amount)}</dd>
    </div>
  )
}

function DetailMessage({
  title,
  description,
  onBack,
  onRetry,
}: {
  title: string
  description: string
  onBack: () => void
  onRetry?: () => void
}) {
  return (
    <main aria-label="배송요청 상세" className="mx-auto flex min-h-screen w-full max-w-[604px] items-center justify-center bg-surface px-5">
      <div role="alert" className="flex w-full max-w-sm flex-col items-center rounded-3xl bg-error-container px-6 py-10 text-center text-on-error-container">
        <span className="material-symbols-outlined text-4xl">error</span>
        <h1 className="mt-3 text-title-lg font-bold">{title}</h1>
        <p className="mt-2 text-body-md">{description}</p>
        <div className="mt-6 flex w-full gap-2">
          <button type="button" onClick={onBack} className="h-12 flex-1 rounded-xl border border-on-error-container text-label-md font-bold">
            목록으로
          </button>
          {onRetry && (
            <button type="button" onClick={onRetry} className="h-12 flex-1 rounded-xl bg-on-error-container text-label-md font-bold text-on-error">
              다시 시도
            </button>
          )}
        </div>
      </div>
    </main>
  )
}
