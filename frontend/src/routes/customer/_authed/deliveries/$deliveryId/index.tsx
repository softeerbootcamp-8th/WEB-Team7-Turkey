import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, Link, useRouter } from '@tanstack/react-router'
import {
  getGetDeliveriesQueryKey,
  getGetDeliveryQueryKey,
  useCancelDelivery,
  useGetDelivery,
} from '@/api/generated/customer-delivery/customer-delivery'
import type { DeliveryDetailResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { getCustomerDeliveryStatusLabel, isTrackableDeliveryStatus } from '@/shared/delivery/status'
import {
  formatDetailAddress,
  formatDetailContact,
  formatDetailDateTime,
  formatDetailDistance,
  formatDetailFare,
  formatDetailItemType,
  getDeliveryCancelErrorMessage,
  getDeliveryDetailErrorState,
  isDeliveryCancelSuccess,
} from './-deliveryDetail'

export const Route = createFileRoute('/customer/_authed/deliveries/$deliveryId/')({
  component: DeliveryDetail,
})

function DeliveryDetail() {
  const { deliveryId: deliveryIdParam } = Route.useParams()
  const deliveryId = Number(deliveryIdParam)
  const isValidDeliveryId = Number.isSafeInteger(deliveryId) && deliveryId > 0
  const router = useRouter()
  const queryClient = useQueryClient()
  const [showCancelConfirm, setShowCancelConfirm] = useState(false)
  const [cancelError, setCancelError] = useState<string | null>(null)

  const detailQuery = useGetDelivery(deliveryId, {
    query: { enabled: isValidDeliveryId, retry: false },
  })
  const cancelMutation = useCancelDelivery()
  const detail = detailQuery.data?.data
  const invalidResponse = detailQuery.isSuccess && detail == null

  function cancelDelivery() {
    if (!isValidDeliveryId || cancelMutation.isPending) {
      return
    }
    setCancelError(null)
    cancelMutation.mutate(
      { deliveryId, data: {} },
      {
        onSuccess: async (response) => {
          setShowCancelConfirm(false)
          if (!isDeliveryCancelSuccess(response)) {
            setCancelError('취소 결과를 확인하지 못했습니다. 상태를 다시 확인해 주세요.')
            await detailQuery.refetch()
            return
          }
          await Promise.all([
            queryClient.invalidateQueries({ queryKey: getGetDeliveryQueryKey(deliveryId) }),
            queryClient.invalidateQueries({ queryKey: getGetDeliveriesQueryKey() }),
          ])
          await router.navigate({ to: '/customer/deliveries' })
        },
        onError: async (error) => {
          setShowCancelConfirm(false)
          setCancelError(getDeliveryCancelErrorMessage(error))
          if (error.response?.status === 409) {
            await detailQuery.refetch()
          }
        },
      },
    )
  }

  if (!isValidDeliveryId) {
    return <DetailError message="잘못된 배송 번호입니다." notFound />
  }

  if (detailQuery.isPending) {
    return <DetailFeedback icon="progress_activity" message="배송 상세 정보를 불러오고 있어요." spin />
  }

  if (detailQuery.isError || invalidResponse || !detail) {
    const errorState = detailQuery.isError
      ? getDeliveryDetailErrorState(detailQuery.error)
      : { notFound: false, message: '배송 상세 정보를 불러오지 못했습니다.' }
    return (
      <DetailError
        {...errorState}
        onRetry={errorState.notFound ? undefined : () => void detailQuery.refetch()}
      />
    )
  }

  const canCancel = detail.status === 'WAITING'
  const canTrack = isTrackableDeliveryStatus(detail.status)

  return (
    <main aria-label="배송 상세" className="mx-auto min-h-screen w-full max-w-md bg-surface pb-28 text-on-surface shadow-lg">
      <header className="sticky top-0 z-20 flex h-16 items-center border-b border-outline-variant bg-surface-container-lowest px-4">
        <button
          type="button"
          aria-label="배송 목록으로 돌아가기"
          onClick={() => void router.navigate({ to: '/customer/deliveries' })}
          className="flex h-11 w-11 items-center justify-center rounded-full hover:bg-surface-container-low"
        >
          <span className="material-symbols-outlined" aria-hidden="true">arrow_back</span>
        </button>
        <h1 className="flex-1 text-center text-headline-md font-bold">배송 상세</h1>
        <div className="h-11 w-11" aria-hidden="true" />
      </header>

      <div className="flex flex-col gap-5 px-5 py-6">
        <section className="rounded-2xl bg-surface-container-lowest p-5 shadow-sm">
          <div className="flex items-start justify-between gap-3">
            <div>
              <span className="inline-flex rounded-full bg-primary-container px-3 py-1 text-label-md font-bold text-on-primary-container">
                {getCustomerDeliveryStatusLabel(detail.status)}
              </span>
              <h2 className="mt-3 text-headline-lg font-bold">주문 #{detail.deliveryId ?? deliveryId}</h2>
            </div>
            <span className="material-symbols-outlined text-4xl text-primary" aria-hidden="true">package_2</span>
          </div>
          {canTrack && (
            <Link
              to="/customer/deliveries/$deliveryId/tracking"
              params={{ deliveryId: String(deliveryId) }}
              className="mt-5 flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-primary-container text-label-lg font-bold text-on-primary-container"
            >
              <span className="material-symbols-outlined text-xl" aria-hidden="true">near_me</span>
              배송 추적하기
            </Link>
          )}
        </section>

        {cancelError && (
          <p role="alert" className="rounded-xl bg-error-container px-4 py-3 text-body-md font-semibold text-on-error-container">
            {cancelError}
          </p>
        )}

        <InfoSection title="주문 정보">
          <InfoRow label="주문 상태" value={getCustomerDeliveryStatusLabel(detail.status)} strong />
          <InfoRow label="주문 번호" value={`#${detail.deliveryId ?? deliveryId}`} />
          <InfoRow label="물품" value={formatDetailItemType(detail.itemType)} />
          <InfoRow label="요청 시각" value={formatDetailDateTime(detail.requestedAt)} />
          <InfoRow label="직선 거리" value={formatDetailDistance(detail.straightDistanceMeters)} />
          {detail.completedAt && <InfoRow label="완료 시각" value={formatDetailDateTime(detail.completedAt)} />}
          {detail.canceledAt && <InfoRow label="취소 시각" value={formatDetailDateTime(detail.canceledAt)} />}
          {detail.cancelReason && <InfoRow label="취소 사유" value={detail.cancelReason} />}
        </InfoSection>

        <InfoSection title="출발지 정보">
          <InfoRow label="보내는 분" value={formatDetailContact(detail.sender)} />
          <InfoRow label="주소" value={formatDetailAddress(detail.pickup)} />
        </InfoSection>

        <InfoSection title="도착지 정보">
          <InfoRow label="받는 분" value={formatDetailContact(detail.recipient)} />
          <InfoRow label="주소" value={formatDetailAddress(detail.destination)} />
        </InfoSection>

        <FareSection detail={detail} />

        <section className="rounded-2xl bg-surface-container-lowest p-5 shadow-sm">
          <h2 className="text-headline-sm font-bold">인수 정보</h2>
          <div className="mt-4 flex items-center gap-4 rounded-xl bg-surface-container-low p-4 text-secondary">
            <span className="material-symbols-outlined text-3xl" aria-hidden="true">image</span>
            <p className="text-body-md">배송 완료 인증 사진은 준비 중입니다.</p>
          </div>
          {/* TODO(#99): 배송 완료 인증 조회 API 확정 후 사진 연결 */}
        </section>
      </div>

      {canCancel && (
        <div className="fixed inset-x-0 bottom-0 z-20 mx-auto w-full max-w-md border-t border-outline-variant bg-surface-container-lowest p-4">
          <button
            type="button"
            onClick={() => {
              setCancelError(null)
              setShowCancelConfirm(true)
            }}
            disabled={cancelMutation.isPending}
            className="min-h-12 w-full rounded-xl border border-error px-5 text-label-lg font-bold text-error disabled:opacity-50"
          >
            배송요청 취소
          </button>
        </div>
      )}

      {showCancelConfirm && (
        <CancelConfirmDialog
          pending={cancelMutation.isPending}
          onClose={() => setShowCancelConfirm(false)}
          onConfirm={cancelDelivery}
        />
      )}
    </main>
  )
}

function InfoSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-2xl bg-surface-container-lowest p-5 shadow-sm">
      <h2 className="text-headline-sm font-bold">{title}</h2>
      <dl className="mt-5 flex flex-col gap-4">{children}</dl>
    </section>
  )
}

function InfoRow({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="grid grid-cols-[84px_1fr] gap-3 text-body-md">
      <dt className="text-secondary">{label}</dt>
      <dd className={`break-words text-right ${strong ? 'font-bold' : 'font-semibold'}`}>{value}</dd>
    </div>
  )
}

function FareSection({ detail }: { detail: DeliveryDetailResponse }) {
  return (
    <InfoSection title="요금 정보">
      <InfoRow label="기본 요금" value={formatDetailFare(detail.fare?.baseFare)} />
      <InfoRow label="거리 요금" value={formatDetailFare(detail.fare?.distanceFare)} />
      <InfoRow label="물품 할증" value={formatDetailFare(detail.fare?.itemSurcharge)} />
      <div className="border-t border-outline-variant pt-4">
        <InfoRow label="총 요금" value={formatDetailFare(detail.fare?.totalFare)} strong />
      </div>
    </InfoSection>
  )
}

function DetailFeedback({ icon, message, spin = false }: { icon: string; message: string; spin?: boolean }) {
  return (
    <main aria-label="배송 상세" className="mx-auto flex min-h-screen w-full max-w-md items-center justify-center bg-surface px-6">
      <section role="status" className="flex flex-col items-center gap-4 text-center text-secondary">
        <span className={`material-symbols-outlined text-5xl ${spin ? 'animate-spin' : ''}`} aria-hidden="true">{icon}</span>
        <p className="text-body-md font-semibold">{message}</p>
      </section>
    </main>
  )
}

function DetailError({ message, notFound, onRetry }: { message: string; notFound: boolean; onRetry?: () => void }) {
  return (
    <main aria-label="배송 상세 오류" className="mx-auto flex min-h-screen w-full max-w-md items-center justify-center bg-surface px-6">
      <section role="alert" className="flex w-full flex-col items-center gap-4 rounded-2xl bg-error-container px-6 py-12 text-center text-on-error-container">
        <span className="material-symbols-outlined text-5xl" aria-hidden="true">{notFound ? 'search_off' : 'cloud_off'}</span>
        <p className="text-body-md font-semibold">{message}</p>
        <div className="flex w-full gap-3">
          {onRetry && (
            <button type="button" onClick={onRetry} className="min-h-12 flex-1 rounded-xl bg-on-error-container px-4 text-label-lg font-bold text-on-error">
              다시 시도
            </button>
          )}
          <Link to="/customer/deliveries" className="flex min-h-12 flex-1 items-center justify-center rounded-xl border border-on-error-container px-4 text-label-lg font-bold">
            목록으로
          </Link>
        </div>
      </section>
    </main>
  )
}

function CancelConfirmDialog({ pending, onClose, onConfirm }: { pending: boolean; onClose: () => void; onConfirm: () => void }) {
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-4 sm:items-center">
      <section role="dialog" aria-modal="true" aria-labelledby="cancel-title" className="w-full max-w-sm rounded-2xl bg-surface-container-lowest p-6 shadow-xl">
        <h2 id="cancel-title" className="text-headline-md font-bold">배송요청을 취소할까요?</h2>
        <p className="mt-2 text-body-md text-secondary">취소한 배송요청은 되돌릴 수 없습니다.</p>
        <div className="mt-6 flex gap-3">
          <button type="button" onClick={onClose} disabled={pending} className="min-h-12 flex-1 rounded-xl border border-outline-variant text-label-lg font-bold disabled:opacity-50">
            돌아가기
          </button>
          <button type="button" onClick={onConfirm} disabled={pending} className="min-h-12 flex-1 rounded-xl bg-error text-label-lg font-bold text-on-error disabled:opacity-50">
            {pending ? '취소 중…' : '취소하기'}
          </button>
        </div>
      </section>
    </div>
  )
}
