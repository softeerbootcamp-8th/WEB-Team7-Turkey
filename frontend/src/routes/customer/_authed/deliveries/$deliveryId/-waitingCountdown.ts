import { decideExpiry } from '@/shared/hooks/useWaitingExpiryTimer'

/**
 * 배차 대기 자동 취소까지 남은 시간을 `M:SS` 로 포맷한다(#42, 백엔드
 * `DeliveryTimeoutService.WAITING_TIMEOUT` = 5분).
 *
 * <p>판정은 {@link decideExpiry} 하나를 재사용한다 — 실제 자동 취소를 거는
 * `useWaitingExpiryTimer` 와 <b>같은 절대 시각 계산</b>이라, 표시값과 실제 취소 시점이
 * 어긋나지 않는다(같은 상수·같은 `requestedAt` 파싱). 서버는 남은 시간을 주지 않으므로
 * (`requestedAt` 만 내려온다) 이 5분은 클라이언트가 상수로 안다.
 *
 * <p><b>ETA(`formatEtaCountdown`)와 달리 초까지 보여 준다.</b> 5분짜리 하드 마감이라
 * 사용자가 임박을 초 단위로 체감해야 하고, 남은 시간이 짧아 "약 N분"으로 뭉치면
 * 마지막 1분이 통째로 사라진다.
 *
 * @returns 표시 문구(`"5:00"`, `"0:07"`). 이미 만료됐거나 값이 없으면 null — 그때는
 *   호출부가 카운트다운을 숨긴다(만료 처리는 타이머가 하고, 상태가 CANCELED 로 바뀐다).
 */
export function formatWaitingCountdown(
  requestedAt: string | null | undefined,
  nowMs: number,
): string | null {
  if (requestedAt == null) {
    return null
  }
  const decision = decideExpiry(nowMs, requestedAt)
  if (decision.kind === 'expired') {
    return null
  }
  // 올림이라 남은 0.4초도 "0:01" 로 보인다 — 사용자가 아직 시간이 있다고 느끼는 동안
  // "0:00" 을 띄워 두는 착시를 피한다. 진짜 0 이 되는 순간은 만료로 빠져 숨겨진다.
  const totalSeconds = Math.ceil(decision.remainingMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}
