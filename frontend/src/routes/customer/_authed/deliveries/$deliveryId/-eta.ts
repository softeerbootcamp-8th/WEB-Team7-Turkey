import { parseServerDate } from '@/shared/lib/datetime'

/**
 * ETA 가 어느 지점 기준인지는 배송 상태가 정한다(#447, 백엔드 `DeliveryRouteEstimator.targetOf`).
 * 서버가 응답에 `status` 를 함께 담아 주므로 그 값으로 라벨을 고른다 — 다른 조회의 상태를 쓰면
 * 전이 순간에 "픽업까지"라는 라벨과 도착지 기준 값이 섞인다.
 */
export function getEtaLabel(status: string | null | undefined): string {
  switch (status) {
    case 'ASSIGNED':
    case 'MOVING_TO_PICKUP':
      return '픽업까지'
    case 'PICKED_UP':
    case 'DELIVERING':
      return '도착까지'
    default:
      return '도착까지'
  }
}

/**
 * 남은 시간을 분 단위로 센다.
 *
 * <p>서버가 <b>절대 시각</b>을 주기 때문에 이 계산이 성립한다 — 폴링이 1분 주기여도 그 사이
 * 화면이 멈춰 보이지 않도록 클라이언트가 스스로 줄여 나간다(사람 확인, 2026-08-10).
 *
 * <p><b>예정 시각이 지나면 "곧 도착"에 멈춘다.</b> 음수를 "3분 지연"처럼 보여 주는 대안은
 * 택하지 않았다 — OSRM 은 실시간 교통정보가 없는 자유흐름 기준이라 원래 낙관적이고, 그러면
 * 정상 배송도 상시 지연으로 보인다. 실제 지연 여부는 상태 전이(SSE)가 알려 준다.
 *
 * @returns 표시 문구. 값이 없거나 파싱 불가면 null — 그때는 호출부가 ETA 영역을 숨긴다
 */
export function formatEtaCountdown(
  estimatedArrivalAt: string | null | undefined,
  now: Date = new Date(),
): string | null {
  const arrival = parseServerDate(estimatedArrivalAt)
  if (!arrival) {
    return null
  }

  const remainingMs = arrival.getTime() - now.getTime()
  // 1분 미만(음수 포함)은 전부 여기로 모은다. 0분·-1분을 따로 보여 줄 이유가 없다.
  if (remainingMs < 60_000) {
    return '곧 도착'
  }
  return `약 ${Math.floor(remainingMs / 60_000)}분`
}
