import { useGetRiderSettlements } from '@/api/generated/rider-point/rider-point'
import { isSameHistoryDay, toRiderDeliveryHistory } from './-riderHistory'

const HISTORY_FETCH_SIZE = 100

/**
 * 전용 운행 기록 API가 구현되기 전까지 기존 정산 조회 결과에서 배송 식별자와 완료 시각만 사용한다.
 * 금액 등 포인트 정보는 화면 모델에 노출하지 않아 이후 운행 기록 API로 교체하기 쉽다.
 */
export function useRiderDeliveryHistory(selectedDate: Date) {
  const query = useGetRiderSettlements(
    { page: 0, size: HISTORY_FETCH_SIZE },
    { query: { retry: false, placeholderData: (previous) => previous } },
  )
  const allItems = toRiderDeliveryHistory(query.data?.data?.items ?? [])
  const items = allItems.filter((item) => isSameHistoryDay(item.completedAt, selectedDate))

  return {
    ...query,
    items,
    summary: {
      completed: items.length,
      canceled: 0,
    },
  }
}
