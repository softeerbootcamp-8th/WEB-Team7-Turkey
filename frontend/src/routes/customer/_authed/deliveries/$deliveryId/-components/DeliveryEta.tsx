import { useEffect, useRef, useState } from 'react'
import { useGetCustomerDeliveryEta } from '@/api/generated/customer-delivery/customer-delivery'
import { formatEtaCountdown, getEtaLabel } from '../-eta'

interface DeliveryEtaProps {
  deliveryId: number
  /** 추적 가능한 상태(ASSIGNED~DELIVERING)일 때만 폴링한다. */
  enabled: boolean
  /**
   * 현재 배송 상태. 이 값이 바뀌면 60초 폴링을 기다리지 않고 ETA 를 즉시 다시 조회한다.
   * ETA 는 별도 쿼리라 상태 전이(SSE)로 자동 재조회되지 않는데, 그러면 픽업→배송 전이 후에도
   * 다음 폴링 전까지 최대 60초 동안 "픽업까지" 라벨·픽업 기준 값이 남아 나머지 UI와 어긋난다.
   */
  status?: string
}

/** 서버 재조회 주기. 백엔드 호출 하나가 OSRM 호출 하나라 이 값이 곧 경로 서버 부하다(#447). */
const POLL_INTERVAL_MS = 60_000

/**
 * 화면 눈금 갱신 주기. 표시 단위가 분이라 10초면 충분하고, 1초 tick 은 초당 리렌더를 만든다.
 * 폴링(1분)과 별개인 이유는 <b>서버가 절대 시각을 주기 때문</b>이다 — 그 사이를 클라이언트가
 * 스스로 줄여 나가지 않으면 ETA 가 1분씩 멈춰 있는 것처럼 보인다.
 */
const TICK_INTERVAL_MS = 10_000

/**
 * 도착 예정 시각 표시(#447).
 *
 * <p><b>추적 화면에서 분리한 이유는 tick 때문이다.</b> 카운트다운 상태를 페이지 컴포넌트에 두면
 * 10초마다 지도까지 리렌더된다. 이 컴포넌트만 다시 그리게 가둔다.
 *
 * <p><b>산정 불가는 오류가 아니다.</b> 서버가 200 + `estimatedArrivalAt: null` 로 주므로
 * (배차 전·완료·취소, 라이더 위치 없음, 경로 서버 장애) 그때는 이 영역을 통째로 숨긴다 —
 * "계산 중" 같은 문구를 띄우면 영영 오지 않을 값을 기다리는 것처럼 보인다.
 */
export function DeliveryEta({ deliveryId, enabled, status }: DeliveryEtaProps) {
  const etaQuery = useGetCustomerDeliveryEta(deliveryId, {
    query: {
      enabled,
      // 탭이 백그라운드면 멈춘다(기본값). 안 보는 화면 때문에 OSRM 을 부를 이유가 없다.
      refetchInterval: POLL_INTERVAL_MS,
      retry: false,
    },
  })

  const [now, setNow] = useState(() => new Date())

  useEffect(() => {
    if (!enabled) {
      return
    }
    const timer = setInterval(() => setNow(new Date()), TICK_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [enabled])

  // 상태가 바뀌면(예: PICKED_UP→DELIVERING) 폴링을 기다리지 않고 즉시 재조회해 라벨·기준점을 맞춘다.
  // 초기 마운트 때는 쿼리가 이미 조회하므로 값이 실제로 바뀔 때만 부른다.
  const previousStatus = useRef(status)
  useEffect(() => {
    if (!enabled || previousStatus.current === status) {
      return
    }
    previousStatus.current = status
    void etaQuery.refetch()
    // etaQuery.refetch 는 참조가 안정적이라 의존성에서 제외한다(넣으면 매 렌더 재조회).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, enabled])

  const eta = etaQuery.data?.data
  const countdown = formatEtaCountdown(eta?.estimatedArrivalAt, now)

  // PICKED_UP 은 물품을 실었지만 아직 배송 출발(DELIVERING) 전이라, "도착까지"를 미리 띄우면
  // 실제로 이동을 시작하지 않았는데 도착 예정이 뜨는 셈이라 혼란스럽다 → 이 단계는 ETA 를 숨긴다.
  if (!enabled || !countdown || status === 'PICKED_UP') {
    return null
  }

  return (
    <p aria-live="polite" className="text-sm text-gray-500">
      {getEtaLabel(eta?.status)} <span className="font-bold text-gray-900">{countdown}</span>
    </p>
  )
}
