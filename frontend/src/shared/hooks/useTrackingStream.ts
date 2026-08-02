import { useEffect, useState } from 'react'

export type TrackingConnectionStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'error'

export interface LocationPing {
  latitude: number
  longitude: number
  measuredAt: string
  accuracyMeters: number | null
}

export interface UseTrackingStreamResult {
  status: TrackingConnectionStatus
  location: LocationPing | null
}

/**
 * SSE `data:` 페이로드를 위치 값으로 파싱한다. 형식이 어긋나면 예외 대신 null을 돌려준다 —
 * 백엔드가 페이로드를 파싱 없이 그대로 흘려보내므로(location/sse), 계약이 어긋난 프레임
 * 하나가 스트림 전체(이후 이벤트 처리)를 멈추면 안 된다.
 */
export function parseLocationPing(raw: string): LocationPing | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }

  if (typeof parsed !== 'object' || parsed === null) {
    return null
  }
  const candidate = parsed as Record<string, unknown>
  if (
    typeof candidate.latitude !== 'number' ||
    typeof candidate.longitude !== 'number' ||
    typeof candidate.measuredAt !== 'string'
  ) {
    return null
  }

  return {
    latitude: candidate.latitude,
    longitude: candidate.longitude,
    measuredAt: candidate.measuredAt,
    accuracyMeters: typeof candidate.accuracyMeters === 'number' ? candidate.accuracyMeters : null,
  }
}

function trackingStreamUrl(deliveryId: number): string {
  return `/api/customer/deliveries/${deliveryId}/tracking/stream`
}

/**
 * 배송 실시간 위치 SSE 구독.
 *
 * 배송 상태는 여기서 받지 않는다 — 지금 백엔드는 위치만 이름 없는 기본 `message` 이벤트로
 * 중계한다(위치 추적 단순화, #297). 상태 변경을 실시간으로 미는 이벤트는 아직 없으므로,
 * 화면은 초기 REST 조회값을 상태 표시에 쓰고 이 훅은 위치 갱신에만 쓴다.
 *
 * @param deliveryId 구독할 배송 ID.
 * @param enabled `false`거나 `deliveryId`가 없으면 연결하지 않는다 — 배차 전(WAITING)이거나
 *                이미 종료된 배송은 서버가 404/409로 거부하므로 애초에 시도하지 않는다.
 */
export function useTrackingStream(deliveryId: number | undefined, enabled: boolean): UseTrackingStreamResult {
  const [status, setStatus] = useState<TrackingConnectionStatus>('idle')
  const [location, setLocation] = useState<LocationPing | null>(null)

  useEffect(() => {
    if (!enabled || deliveryId == null) {
      setStatus('idle')
      return
    }

    setStatus('connecting')
    setLocation(null)
    const source = new EventSource(trackingStreamUrl(deliveryId), { withCredentials: true })

    source.onopen = () => {
      setStatus('open')
    }

    source.onmessage = (event: MessageEvent<string>) => {
      const ping = parseLocationPing(event.data)
      if (ping) {
        setLocation(ping)
      }
    }

    source.onerror = () => {
      // 브라우저 EventSource는 서버가 200이 아닌 응답을 준 게 아니라면 자동으로 재연결을
      // 시도한다(readyState가 CONNECTING으로 돌아감). CLOSED면 브라우저가 재시도를 포기한
      // 것이다(예: 401/404/409 응답).
      setStatus(source.readyState === EventSource.CLOSED ? 'error' : 'reconnecting')
    }

    return () => {
      source.close()
    }
  }, [deliveryId, enabled])

  return { status, location }
}
