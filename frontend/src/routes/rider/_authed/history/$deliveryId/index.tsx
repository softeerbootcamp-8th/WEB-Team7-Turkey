import { createFileRoute, useRouter } from '@tanstack/react-router'
import { useGetDeliveryHistoryDetail } from '@/api/generated/rider-history/rider-history'
import {
  formatDetailDate,
  formatDetailTime,
  formatDistance,
  formatItemType,
  formatMoney,
  formatProofType,
  getDetailErrorMessage,
  resolveCompletedAt,
  toTimelineSteps,
} from './-riderHistoryDetail'

export const Route = createFileRoute('/rider/_authed/history/$deliveryId/')({
  component: RiderHistoryDetail,
})

function RiderHistoryDetail() {
  const { deliveryId: deliveryIdParam } = Route.useParams()
  const router = useRouter()

  const deliveryId = Number(deliveryIdParam)
  const isValidDeliveryId = Number.isSafeInteger(deliveryId) && deliveryId > 0
  const detailQuery = useGetDeliveryHistoryDetail(deliveryId, {
    query: { enabled: isValidDeliveryId, retry: false },
  })

  function goToList() {
    void router.navigate({ to: '/rider/history' })
  }

  if (!isValidDeliveryId) {
    return (
      <DetailFeedback
        message="잘못된 접근입니다. 목록에서 운행 기록을 다시 선택해 주세요."
        onBack={goToList}
      />
    )
  }

  if (detailQuery.isPending) {
    return (
      <main aria-label="운행 상세 정보" className="bg-background text-on-background flex min-h-screen items-center justify-center px-container-margin">
        <div aria-live="polite" className="flex flex-col items-center gap-3 text-center">
          <span className="material-symbols-outlined animate-spin text-4xl text-tertiary">progress_activity</span>
          <p className="font-body-md text-body-md text-secondary">운행 상세 정보를 불러오고 있어요.</p>
        </div>
      </main>
    )
  }

  const detail = detailQuery.data?.data
  if (detailQuery.isError || !detail) {
    return (
      <DetailFeedback
        message={detailQuery.isError ? getDetailErrorMessage(detailQuery.error) : '운행 상세 정보가 없습니다.'}
        onBack={goToList}
        onRetry={() => void detailQuery.refetch()}
      />
    )
  }

  const completedAt = resolveCompletedAt(detail.steps, detail.settledAt)
  const steps = toTimelineSteps(detail.steps)
  const fare = detail.finalFare

  return (
    <div className="bg-background text-on-background font-body-md min-h-screen pb-xl">
      {/* TopAppBar */}
      <header className="bg-surface dark:bg-surface-dim docked full-width top-0 sticky z-50 transition-all duration-200">
        <div className="flex justify-between items-center w-full px-container-margin h-14 border-b border-surface-variant">
          <button
            type="button"
            aria-label="운행 기록 목록으로 돌아가기"
            onClick={goToList}
            className="text-primary dark:text-primary-fixed hover:bg-surface-container-high dark:hover:bg-surface-container-highest p-2 rounded-full transition-all duration-200 active:scale-95 flex items-center justify-center"
          >
            <span className="material-symbols-outlined">arrow_back</span>
          </button>
          <h1 className="font-headline-md text-headline-md font-bold text-on-surface dark:text-on-surface-variant">운행 상세 정보</h1>
          <div className="w-10"></div> {/* Spacer for centering */}
        </div>
      </header>
      <main aria-label="운행 상세 정보" className="px-container-margin py-md flex flex-col gap-md max-w-2xl mx-auto">
        {/* Section 1: Delivery Status */}
        <section className="bg-surface-container-lowest rounded-xl p-md shadow-[0_4px_12px_rgba(0,0,0,0.08)] flex justify-between items-center">
          <div>
            <p className="font-label-sm text-label-sm text-secondary mb-xs">{formatDetailDate(completedAt)} {formatDetailTime(completedAt)}</p>
            <div className="flex items-center gap-sm">
              <span className="bg-[#EBF3FF] text-[#2D7FF9] px-3 py-1 rounded-full font-label-md text-label-md">정산 완료</span>
              <h2 className="font-headline-sm text-headline-sm text-on-surface">#{detail.deliveryId ?? deliveryId}</h2>
            </div>
          </div>
        </section>
        {/* Section 2: Route Information */}
        <section className="bg-surface-container-lowest rounded-xl p-md shadow-[0_4px_12px_rgba(0,0,0,0.08)]">
          <h3 className="font-label-lg text-label-lg text-secondary mb-md">경로 정보</h3>
          <div className="flex flex-col gap-4 relative">
            {/* Vertical Line Connector */}
            <div className="absolute left-3 top-6 bottom-6 w-[2px] bg-surface-variant z-0"></div>
            {/* From */}
            <div className="flex items-start gap-md relative z-10">
              <div className="w-6 h-6 rounded-full bg-surface-variant flex items-center justify-center mt-0.5 border-2 border-surface-container-lowest">
                <span className="w-2 h-2 rounded-full bg-secondary"></span>
              </div>
              <div className="flex-1">
                <p className="font-label-sm text-label-sm text-secondary mb-xs">출발지</p>
                <p className="font-body-lg text-body-lg font-semibold text-on-surface">{detail.pickup?.roadAddress || '출발지 정보 없음'}</p>
                {detail.pickup?.detailAddress && <p className="font-body-md text-body-md text-secondary mt-1">{detail.pickup.detailAddress}</p>}
              </div>
            </div>
            {/* To */}
            <div className="flex items-start gap-md relative z-10">
              <div className="w-6 h-6 rounded-full bg-primary-container flex items-center justify-center mt-0.5 border-2 border-surface-container-lowest">
                <span className="w-2 h-2 rounded-full bg-[#191919]"></span>
              </div>
              <div className="flex-1">
                <p className="font-label-sm text-label-sm text-secondary mb-xs">도착지</p>
                <p className="font-body-lg text-body-lg font-semibold text-on-surface">{detail.destination?.roadAddress || '도착지 정보 없음'}</p>
                {detail.destination?.detailAddress && <p className="font-body-md text-body-md text-secondary mt-1">{detail.destination.detailAddress}</p>}
              </div>
            </div>
          </div>
          <p className="font-label-sm text-label-sm text-secondary mt-md">이동 거리 {formatDistance(detail.straightDistanceMeters)}</p>
          {/* 지도 SDK 미결(#209)로 지도 영역은 축소함 */}
        </section>
        {/* Section 3: Item Information */}
        <section className="bg-surface-container-lowest rounded-xl p-md shadow-[0_4px_12px_rgba(0,0,0,0.08)]">
          <h3 className="font-label-lg text-label-lg text-secondary mb-md">물품 정보</h3>
          <div className="flex items-center gap-md bg-surface-container-low p-sm rounded-lg border border-surface-variant">
            <div className="w-12 h-12 bg-surface rounded-lg flex items-center justify-center text-secondary shadow-sm">
              <span className="material-symbols-outlined" style={{ fontSize: '24px' }}>inventory_2</span>
            </div>
            <div>
              <p className="font-body-lg text-body-lg font-semibold text-on-surface">{formatItemType(detail.itemType)}</p>
            </div>
          </div>
          <div className="mt-sm flex justify-between items-center">
            <p className="font-label-sm text-label-sm text-secondary">배송 완료 인증</p>
            <p className="font-body-md text-body-md text-on-surface">
              {formatProofType(detail.proofType)}
              {detail.proofType && detail.proofType !== 'PHOTO' && detail.proofValue ? ` · ${detail.proofValue}` : ''}
            </p>
          </div>
        </section>
        {/* Section 4: History Timeline */}
        <section className="bg-surface-container-lowest rounded-xl p-md shadow-[0_4px_12px_rgba(0,0,0,0.08)]">
          <h3 className="font-label-lg text-label-lg text-secondary mb-md">상태 이력</h3>
          {steps.length === 0 ? (
            <p className="font-body-md text-body-md text-secondary">상태 이력이 없습니다.</p>
          ) : (
            <ol className="flex flex-col gap-0 relative ml-2">
              {/* Line */}
              <div className="absolute left-2.5 top-2 bottom-6 w-[2px] bg-surface-variant z-0" aria-hidden="true"></div>
              {steps.map((step, index) => {
                const isLast = index === steps.length - 1
                return (
                  <li key={`${step.status}-${step.time}`} className={`flex gap-md relative z-10 ${isLast ? '' : 'pb-md'}`}>
                    {isLast ? (
                      <div className="w-6 h-6 rounded-full bg-primary-container border-2 border-primary-container flex items-center justify-center">
                        <span className="material-symbols-outlined text-[#191919]" style={{ fontSize: '14px', fontWeight: 700 }}>check</span>
                      </div>
                    ) : (
                      <div className="w-6 h-6 rounded-full bg-white border-2 border-surface-variant flex items-center justify-center">
                        <span className="w-2 h-2 rounded-full bg-secondary"></span>
                      </div>
                    )}
                    <div className="flex-1 -mt-0.5">
                      <p className={`font-body-md text-body-md ${isLast ? 'font-bold text-primary' : 'font-semibold text-on-surface'}`}>{step.label}</p>
                      <p className="font-label-sm text-label-sm text-secondary">{step.time}</p>
                    </div>
                  </li>
                )
              })}
            </ol>
          )}
        </section>
        {/* Section 5: Settlement Detail */}
        <section className="bg-surface-container-lowest rounded-xl p-md shadow-[0_4px_12px_rgba(0,0,0,0.08)] mb-xl">
          <h3 className="font-label-lg text-label-lg text-secondary mb-md border-b border-surface-variant pb-2">정산 내역</h3>
          <div className="flex flex-col gap-sm mt-md">
            <div className="flex justify-between items-center">
              <span className="font-body-md text-body-md text-on-surface">기본 운임</span>
              <span className="font-body-md text-body-md text-on-surface">{formatMoney(fare?.baseFare)}</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="font-body-md text-body-md text-on-surface">거리 운임</span>
              <span className="font-body-md text-body-md text-on-surface">{formatMoney(fare?.distanceFare)}</span>
            </div>
            {fare?.itemSurcharge != null && fare.itemSurcharge > 0 && (
              <div className="flex justify-between items-center">
                <span className="font-body-md text-body-md text-on-surface">물품 할증</span>
                <span className="font-body-md text-body-md text-on-surface">{formatMoney(fare.itemSurcharge)}</span>
              </div>
            )}
            <div className="flex justify-between items-center mt-sm pt-sm border-t border-surface-variant border-dashed">
              <span className="font-body-lg text-body-lg font-semibold text-on-surface">확정 운임</span>
              <span className="font-body-lg text-body-lg font-semibold text-on-surface">{formatMoney(fare?.totalFare)}</span>
            </div>
          </div>
          <div className="mt-md bg-surface-container-low p-md rounded-lg flex justify-between items-center border border-primary-container">
            <span className="font-headline-sm text-headline-sm font-bold text-on-surface">최종 정산 금액</span>
            <span className="font-headline-lg text-headline-lg font-bold text-primary">{formatMoney(detail.settlementAmount)}</span>
          </div>
          {detail.settledAt && (
            <p className="font-label-sm text-label-sm text-secondary mt-sm text-right">정산 시각 {formatDetailDate(detail.settledAt)} {formatDetailTime(detail.settledAt)}</p>
          )}
        </section>
      </main>
    </div>
  )
}

interface DetailFeedbackProps {
  message: string
  onBack: () => void
  onRetry?: () => void
}

function DetailFeedback({ message, onBack, onRetry }: DetailFeedbackProps) {
  return (
    <main aria-label="운행 상세 정보" className="bg-background text-on-background flex min-h-screen items-center justify-center px-container-margin">
      <div role="alert" className="flex flex-col items-center gap-4 text-center max-w-sm">
        <span className="material-symbols-outlined text-4xl text-secondary">error</span>
        <p className="font-body-md text-body-md text-on-surface">{message}</p>
        <div className="flex gap-3">
          {onRetry && (
            <button
              type="button"
              onClick={onRetry}
              className="bg-surface-container-high text-on-surface px-4 py-2 rounded-lg font-label-lg text-label-lg transition-all active:scale-95"
            >
              다시 시도
            </button>
          )}
          <button
            type="button"
            onClick={onBack}
            className="bg-primary text-on-primary px-4 py-2 rounded-lg font-label-lg text-label-lg transition-all active:scale-95"
          >
            목록으로
          </button>
        </div>
      </div>
    </main>
  )
}
