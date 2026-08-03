import type { RiderDeliveryRequestSummaryResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import {
  formatDistance,
  formatItemType,
  formatRequestedAt,
  formatSettlement,
} from '../-requestList'

interface RequestCardProps {
  request: RiderDeliveryRequestSummaryResponse
  onSelect: (deliveryId: number) => void
}

export function RequestCard({ request, onSelect }: RequestCardProps) {
  const requestedAt = formatRequestedAt(request.requestedAt)
  const selectable = request.deliveryId != null

  return (
    <button
      type="button"
      disabled={!selectable}
      onClick={() => request.deliveryId != null && onSelect(request.deliveryId)}
      className="w-full border-b border-outline-variant/60 bg-surface-container-lowest px-5 py-4 text-left transition-colors hover:bg-surface-container-low disabled:cursor-not-allowed disabled:opacity-60"
      aria-label={`${request.pickupRoadAddress ?? '출발지 정보 없음'}에서 ${request.destinationRoadAddress ?? '도착지 정보 없음'}으로 가는 콜 상세 보기`}
    >
      <div className="mb-3 flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <span className="rounded-full bg-primary-container px-2.5 py-1 text-label-sm font-bold text-on-primary-container">
            {formatItemType(request.itemType)}
          </span>
          {requestedAt && <span className="text-label-sm text-secondary">{requestedAt} 요청</span>}
        </div>
        <span className="text-headline-sm font-bold text-on-surface">
          {formatSettlement(request.expectedSettlementAmount)}
        </span>
      </div>

      <div className="grid grid-cols-[24px_1fr] gap-x-3 gap-y-3">
        <div className="flex flex-col items-center pt-1">
          <span className="h-2.5 w-2.5 rounded-full bg-tertiary" />
          <span className="my-1 h-6 w-px border-l border-dashed border-outline-variant" />
          <span className="h-2.5 w-2.5 rounded-full bg-primary" />
        </div>
        <div className="min-w-0 space-y-3">
          <div>
            <div className="mb-0.5 flex items-center gap-2 text-label-sm text-secondary">
              <span>픽업</span>
              <span className="font-bold text-tertiary">{formatDistance(request.distanceToPickupMeters)}</span>
            </div>
            <p className="truncate text-body-lg font-bold text-on-surface">
              {request.pickupRoadAddress ?? '출발지 정보가 없습니다.'}
            </p>
          </div>
          <div>
            <div className="mb-0.5 flex items-center gap-2 text-label-sm text-secondary">
              <span>도착</span>
              <span>{formatDistance(request.straightDistanceMeters)}</span>
            </div>
            <p className="truncate text-body-lg font-semibold text-on-surface">
              {request.destinationRoadAddress ?? '도착지 정보가 없습니다.'}
            </p>
          </div>
        </div>
      </div>
    </button>
  )
}
