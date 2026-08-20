import { useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  getGetDeliveryQueryKey,
  useCancelCustomerDelivery,
} from '@/api/generated/customer-delivery/customer-delivery'
import { parseServerDate } from '@/shared/lib/datetime'

/** 배차 대기 타임아웃(백엔드 DeliveryTimeoutService.WAITING_TIMEOUT과 반드시 맞춘다). */
export const WAITING_TIMEOUT_MS = 100 * 60 * 1000

const AUTO_CANCEL_REASON = '배차 대기 시간을 초과해 자동 취소되었습니다.'

export type ExpiryDecision =
  | { kind: 'expired' }
  | { kind: 'pending'; remainingMs: number }

/**
 * `nowMs` 시점에 `requestedAt`(주문 생성 시각)이 이미 만료됐는지 판정한다. 절대 시각 비교라
 * 몇 번을 놓쳤든(백그라운드 등) 판정하는 순간 항상 정확하다 — 순수 함수라 훅과 분리해 테스트한다.
 *
 * `requestedAt`은 백엔드가 UTC로 만들지만 `Z`/오프셋 없이 내려온다(`shared/lib/datetime.ts`
 * 참고). `new Date()`로 직접 파싱하면 타임존 지정자가 없는 문자열을 브라우저 로컬 시간대로
 * 해석해버려, 한국(UTC+9)에서는 방금 생성된 주문도 9시간 전에 만료된 것으로 오판해 즉시
 * 취소된다(실측으로 확인함) — 그래서 `parseServerDate`로 UTC 명시 파싱한다.
 */
export function decideExpiry(nowMs: number, requestedAt: string): ExpiryDecision {
  const requestedAtDate = parseServerDate(requestedAt)
  if (!requestedAtDate) {
    // 파싱 불가한 값을 만료로 오판해 잘못 취소하지 않는다 — 스캐너가 최종 백스톱이다.
    return { kind: 'pending', remainingMs: WAITING_TIMEOUT_MS }
  }
  const expiresAt = requestedAtDate.getTime() + WAITING_TIMEOUT_MS
  const remainingMs = expiresAt - nowMs
  if (remainingMs <= 0) {
    return { kind: 'expired' }
  }
  return { kind: 'pending', remainingMs }
}

/**
 * WAITING 주문의 논리적 만료(생성 + 5분) 시각이 지나면 기존 수동 취소 API(#47)를 대신 호출한다.
 * 새 API가 필요 없는 이유: 그 API는 시간 무관·멱등이라(WAITING이면 언제든 취소, 이미 CANCELED면
 * 그대로 200) 타이머가 그냥 "판정 시점에 맞춰 기존 취소 버튼을 대신 눌러주는 것"으로 충분하다
 * (Discussion #402, 이슈 #444).
 *
 * 판정은 세 지점에서 일어난다 — mount, 예약된 setTimeout이 실제로 발화하는 시점, 그리고
 * visibilitychange(포그라운드 복귀, setTimeout이 백그라운드 중 못 불렸을 경우의 보험). 절대
 * 시각(expiresAt)과 비교하는 방식이라 몇 번을 놓쳤든 다시 판정하는 순간 항상 정확하다.
 *
 * 상태가 이미 WAITING이 아니면(배차 확정·완료·취소 등) 아무 일도 하지 않는다.
 */
export function useWaitingExpiryTimer(
  deliveryId: number | undefined,
  status: string | undefined,
  requestedAt: string | undefined,
): void {
  const queryClient = useQueryClient()
  const cancelMutation = useCancelCustomerDelivery()
  const mutateRef = useRef(cancelMutation.mutate)
  mutateRef.current = cancelMutation.mutate

  useEffect(() => {
    if (status !== 'WAITING' || deliveryId == null || requestedAt == null) {
      return
    }

    let timeoutId: ReturnType<typeof setTimeout> | null = null
    let firing = false

    const check = () => {
      if (timeoutId != null) {
        clearTimeout(timeoutId)
        timeoutId = null
      }
      const decision = decideExpiry(Date.now(), requestedAt)
      if (decision.kind === 'pending') {
        timeoutId = setTimeout(check, decision.remainingMs)
        return
      }
      if (firing) {
        return
      }
      firing = true
      mutateRef.current(
        { deliveryId, data: { reason: AUTO_CANCEL_REASON } },
        {
          onSettled: () => {
            firing = false
            void queryClient.invalidateQueries({ queryKey: getGetDeliveryQueryKey(deliveryId) })
          },
        },
      )
    }

    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        check()
      }
    }

    check()
    document.addEventListener('visibilitychange', onVisibilityChange)

    return () => {
      if (timeoutId != null) {
        clearTimeout(timeoutId)
      }
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [deliveryId, status, requestedAt, queryClient])
}
