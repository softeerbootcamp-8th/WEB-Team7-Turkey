import { createFileRoute, Link } from '@tanstack/react-router'
import { useEffect } from 'react'
import {
  useCancelCustomerDelivery,
  useGetDelivery,
} from '@/api/generated/customer-delivery/customer-delivery'
import type {
  DeliveryDetailResponseItemType,
  DeliveryDetailResponseStatus,
  DeliveryStatusStepResponseStatus,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { getCustomerDeliveryStatusLabel, isActiveDeliveryStatus, isTrackableDeliveryStatus } from '@/shared/delivery/status'
import { useTrackingStream, type TrackingConnectionStatus } from '@/shared/hooks/useTrackingStream'
import { useWaitingExpiryTimer } from '@/shared/hooks/useWaitingExpiryTimer'
import { DeliveryEta } from './-components/DeliveryEta'
import { TrackingMap } from './-components/TrackingMap'

export const Route = createFileRoute('/customer/_authed/deliveries/$deliveryId/tracking')({
  component: DeliveryTracking,
})

const STEP_ORDER: DeliveryStatusStepResponseStatus[] = [
  'ASSIGNED',
  'MOVING_TO_PICKUP',
  'PICKED_UP',
  'DELIVERING',
  'COMPLETED',
]

const HEADLINE_BY_STATUS: Record<DeliveryDetailResponseStatus, string> = {
  WAITING: '라이더를 찾고 있어요',
  ASSIGNED: '라이더가 배정됐어요',
  MOVING_TO_PICKUP: '라이더가 픽업지로 이동 중이에요',
  PICKED_UP: '물건을 픽업했어요',
  DELIVERING: '배송 중이에요',
  COMPLETED: '배송이 완료됐어요',
  CANCELED: '배송이 취소됐어요',
}

const ITEM_TYPE_LABELS: Record<DeliveryDetailResponseItemType, string> = {
  DOCUMENT: '문서',
  SMALL_PARCEL: '소형 소포',
  MEDIUM_PARCEL: '중형 소포',
  LARGE_PARCEL: '대형 소포',
  FOOD: '음식',
}

const CONNECTION_LABELS: Record<TrackingConnectionStatus, string> = {
  idle: '',
  connecting: '연결 중…',
  open: '실시간 연결됨',
  reconnecting: '재연결 중…',
  error: '연결이 끊겼어요',
}

function DeliveryTracking() {
  const { deliveryId: deliveryIdParam } = Route.useParams()
  const deliveryId = Number(deliveryIdParam)

  // 다른 탭/기기가 먼저 취소·배차했을 수 있으니, 이 화면으로 포커스가 돌아올 때마다 다시
  // 확인한다(Discussion #402·#432, #444) — 최종 백스톱, 타이머·SSE와 겹쳐 방어한다.
  const detailQuery = useGetDelivery(deliveryId, {
    query: { refetchOnWindowFocus: true, staleTime: 0 },
  })
  const cancelMutation = useCancelCustomerDelivery()

  // ApiResponse<T> 봉투를 벗긴다 — customInstance는 axios 레벨(AxiosResponse<T>)만
  // 언랩하고, 우리 도메인 봉투(success/data/message)는 훅을 쓰는 쪽이 직접 벗겨야 한다
  // (shared/auth/session.ts와 같은 관례).
  //
  // 종료 후(COMPLETED/CANCELED)는 추적 스냅샷(useGetDeliveryTracking)이 409로 거부한다
  // (DeliveryTrackingAccessService.authorizeTracking) — SSE와 같은 게이트라 "추적 불가" 자체가
  // 정상 신호다. 이 화면은 상태·마커를 상태 무관하게 항상 보여줘야 해서, 그 게이트를 타지 않는
  // 상세 조회(useGetDelivery)만으로 렌더링한다.
  const detail = detailQuery.data?.data
  // 지도에 위치를 보여줄 수 있는 상태(라이더 배정 이후)와 SSE를 구독할 수 있는 상태(#401부터는
  // WAITING도 포함)는 다른 질문이다 — WAITING은 보여줄 위치가 없을 뿐, 배차 전이(ASSIGNED)
  // 알림은 받아야 하므로 연결은 미리 열어 둔다.
  const isTrackable = isTrackableDeliveryStatus(detail?.status)
  const isSubscribable = isActiveDeliveryStatus(detail?.status)
  const { status: streamStatus, location, statusChangedAt } = useTrackingStream(deliveryId, isSubscribable)

  // 논리적 만료(생성+5분) 이후 물리적 만료(스캐너)를 기다리지 않고, 화면이 보고 있는 시점마다
  // 스스로 판정해 기존 취소 API를 대신 호출한다(Discussion #402, #444).
  useWaitingExpiryTimer(deliveryId, detail?.status, detail?.requestedAt)

  // 상태 전이 SSE 프레임(#398)을 받으면 상세 조회를 재조회해 화면을 갱신한다(#399).
  // 재조회는 멱등이라 이벤트 순서 역전·중복 걱정이 없다.
  useEffect(() => {
    if (statusChangedAt == null) {
      return
    }
    detailQuery.refetch()
  }, [statusChangedAt])

  if (detailQuery.isLoading) {
    return (
      <div className="w-full max-w-md min-h-screen flex items-center justify-center bg-white text-sm text-gray-500">
        불러오는 중…
      </div>
    )
  }

  if (detailQuery.isError || !detail) {
    return (
      <div role="alert" className="w-full max-w-md min-h-screen flex flex-col items-center justify-center gap-4 bg-white px-6 text-center">
        <p className="text-sm text-gray-600">배송 정보를 불러오지 못했습니다. 본인 주문이 맞는지 확인해 주세요.</p>
        <button
          type="button"
          onClick={() => detailQuery.refetch()}
          className="text-sm font-medium text-blue-600 border border-blue-600 rounded-lg px-4 py-2"
        >
          다시 시도
        </button>
      </div>
    )
  }

  const reachedSteps = new Set((detail.steps ?? []).map((step) => step.status))
  const canCancel = detail.status === 'WAITING'

  function handleCancel() {
    if (!window.confirm('배송요청을 취소할까요?')) {
      return
    }
    cancelMutation.mutate(
      { deliveryId, data: {} },
      { onSuccess: () => detailQuery.refetch() },
    )
  }

  return (
    <div className="relative w-full max-w-md min-h-screen bg-white shadow-xl flex flex-col">
      {/* BEGIN: Map / Waiting-Final area */}
      <div className="w-full h-64 relative shrink-0">
        <TrackingMap
          location={isTrackable ? location : null}
          pickup={detail?.pickup}
          destination={detail?.destination}
          isTrackable={isTrackable}
        />
      </div>
      {/* END: Map / Waiting-Final area */}

      {/* BEGIN: Status Sheet */}
      <div className="relative z-10 w-full bg-white rounded-t-3xl shadow-lg flex-1 flex flex-col -mt-6">
        <header className="flex justify-between items-center p-5 pt-8">
          <Link to="/customer" aria-label="Home" className="p-2 -ml-2 rounded-full cursor-pointer transition-colors hover:bg-gray-100">
            <svg className="h-6 w-6 text-slate-800" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" strokeLinecap="round" strokeLinejoin="round"></path>
            </svg>
          </Link>
          {canCancel && (
            <button
              type="button"
              onClick={handleCancel}
              disabled={cancelMutation.isPending}
              className="text-sm font-medium text-gray-500 px-2 py-1 -mr-2 rounded-md cursor-pointer transition-colors hover:bg-gray-100 disabled:cursor-default disabled:opacity-50 disabled:hover:bg-transparent"
            >
              주문취소
            </button>
          )}
        </header>

        <main className="flex-1 overflow-y-auto no-scrollbar px-5 pb-8">
          {isSubscribable && (
            <p className="text-xs text-gray-400 mb-2">{CONNECTION_LABELS[streamStatus]}</p>
          )}

          <section className="mt-2 mb-8">
            <h1 className="text-[26px] font-bold leading-tight text-gray-900 mb-1">
              {HEADLINE_BY_STATUS[detail.status ?? 'WAITING']}
            </h1>
            {/* ETA 는 전용 폴링 API 가 담당한다(#447) — 상세 조회 응답에는 없다. */}
            <DeliveryEta deliveryId={deliveryId} enabled={isTrackable} />
          </section>

          {/* BEGIN: Progress Tracker */}
          <section className="mb-10 relative">
            <div className="absolute top-[9px] left-2 right-2 h-[3px] bg-gray-200 -z-10 rounded-full"></div>
            <div className="flex justify-between">
              {STEP_ORDER.map((step) => {
                const reached = reachedSteps.has(step) || detail.status === step
                return (
                  <div key={step} className="flex flex-col items-center">
                    <div
                      className={
                        reached
                          ? 'w-5 h-5 bg-blue-600 rounded-full flex items-center justify-center shadow-[0_0_0_4px_white]'
                          : 'w-5 h-5 bg-white border-2 border-gray-200 rounded-full flex items-center justify-center'
                      }
                    >
                      {reached && <div className="w-2 h-2 bg-white rounded-full"></div>}
                    </div>
                    <span className="max-w-16 text-center text-[11px] font-medium text-gray-500 mt-2">
                      {getCustomerDeliveryStatusLabel(step)}
                    </span>
                  </div>
                )
              })}
            </div>
          </section>
          {/* END: Progress Tracker */}

          {/* BEGIN: Order Details */}
          <section className="border-t border-gray-100 pt-6">
            <h3 className="text-base font-bold text-gray-900 mb-5">
              주문 <span className="font-bold">#{detail.deliveryId}</span>
            </h3>

            <div className="space-y-4 mb-6 relative before:absolute before:left-[11px] before:top-4 before:bottom-4 before:w-[2px] before:bg-gray-100 before:-z-10">
              <div className="flex gap-4">
                <div className="w-6 h-6 bg-blue-50 rounded-full flex items-center justify-center shrink-0 border border-white">
                  <div className="w-2 h-2 bg-blue-500"></div>
                </div>
                <div>
                  <p className="text-sm font-medium text-gray-900 mb-0.5">{detail.sender?.name ?? '-'}</p>
                  <p className="text-[13px] text-gray-500">{detail.pickup?.roadAddress ?? '-'}</p>
                </div>
              </div>
              <div className="flex gap-4">
                <div className="w-6 h-6 bg-red-50 rounded-full flex items-center justify-center shrink-0 border border-white">
                  <div className="w-2 h-2 bg-red-500 rounded-full"></div>
                </div>
                <div>
                  <p className="text-sm font-medium text-gray-900 mb-0.5">{detail.recipient?.name ?? '-'} (도착지)</p>
                  <p className="text-[13px] text-gray-500">{detail.destination?.roadAddress ?? '-'}</p>
                </div>
              </div>
            </div>

            {detail.riderName && (
              <div className="bg-gray-50 rounded-xl p-4 mb-4 flex justify-between items-center">
                <span className="text-sm text-gray-500">라이더</span>
                <span className="text-sm font-medium text-gray-900">
                  {detail.riderName}
                  {detail.riderPhoneNumber ? ` · ${detail.riderPhoneNumber}` : ''}
                </span>
              </div>
            )}

            <div className="bg-gray-50 rounded-xl p-4 space-y-3">
              {detail.itemType && (
                <div className="flex justify-between items-center">
                  <span className="text-sm text-gray-500">물품</span>
                  <span className="text-sm font-medium text-gray-900">{ITEM_TYPE_LABELS[detail.itemType]}</span>
                </div>
              )}
              <div className="flex justify-between items-center">
                <span className="text-sm text-gray-500">결제 금액</span>
                <span className="text-base font-bold text-gray-900">
                  {(detail.fare?.totalFare ?? 0).toLocaleString()}원
                </span>
              </div>
            </div>
          </section>
          {/* END: Order Details */}
        </main>
      </div>
      {/* END: Status Sheet */}
    </div>
  )
}
