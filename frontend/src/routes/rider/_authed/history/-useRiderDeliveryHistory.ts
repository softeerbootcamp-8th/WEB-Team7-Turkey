import { useGetDeliveryHistories } from '@/api/generated/rider-history/rider-history'
import { toRiderDeliveryHistory } from './-riderHistory'

export const HISTORY_PAGE_SIZE = 10

/**
 * 라이더 운행 기록 목록(#70). 전용 운행 기록 API(/api/rider/history)를 소비한다 — 서버가
 * 본인 완료 배송만 최신순으로 정렬해 페이지 단위(10개)로 내려준다. 금액(정산액·운임)은 이 응답에
 * 없다(포인트 화면 소관). 화면 모델은 목록 표시에 필요한 배송 정보만 추린다.
 */
export function useRiderDeliveryHistory(page: number) {
  const query = useGetDeliveryHistories(
    { page, size: HISTORY_PAGE_SIZE },
    { query: { retry: false, placeholderData: (previous) => previous } },
  )
  const items = toRiderDeliveryHistory(query.data?.data?.items ?? [])
  const totalElements = query.data?.data?.totalElements ?? 0
  const totalPages = Math.max(1, Math.ceil(totalElements / HISTORY_PAGE_SIZE))

  return {
    ...query,
    items,
    page,
    totalElements,
    totalPages,
    hasPrevious: page > 0,
    hasNext: page + 1 < totalPages,
  }
}
