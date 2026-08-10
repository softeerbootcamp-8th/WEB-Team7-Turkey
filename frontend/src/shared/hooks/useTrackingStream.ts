import { useEffect, useRef, useState } from 'react'

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
  /** STATUS 프레임을 받을 때마다 바뀌는 신호값(타임스탬프). 값 자체엔 의미가 없고
   *  "재조회하라"는 트리거로만 쓴다 — 상태 표시는 REST가 정본(#399). */
  statusChangedAt: number | null
}

/**
 * SSE 프레임의 판별 필드만 읽는다. 백엔드 값은 소문자 "location"/"status"다
 * (`LocationPayload`/`StatusChangedPayload`, #398). `type`이 없으면(레거시 프레임,
 * 또는 롤링 배포 중 구버전) LOCATION으로 간주해 기존 동작을 유지한다.
 */
export function parseFrameType(raw: string): 'LOCATION' | 'STATUS' {
  try {
    const parsed = JSON.parse(raw)
    if (typeof parsed === 'object' && parsed !== null && (parsed as Record<string, unknown>).type === 'status') {
      return 'STATUS'
    }
  } catch {
    // parseLocationPing이 같은 파싱 실패를 다시 처리한다
  }
  return 'LOCATION'
}

/**
 * LOCATION 프레임에 실려 오는 배송 상태를 읽는다(#449).
 *
 * 상태 전이 이벤트는 전이 시점에 한 번만 오므로 유실되면 다시 오지 않는다. 반면 위치 프레임은
 * BUSY 5초 주기로 계속 오므로, 여기에 실린 상태를 보면 그 유실을 최대 5초 안에 알아챌 수 있다.
 *
 * 값이 없으면(롤링 배포 중 구버전 백엔드) null을 돌려주고, 호출자는 기존 동작을 유지한다.
 */
export function parseFrameStatus(raw: string): string | null {
  try {
    const parsed = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) {
      return null
    }
    const status = (parsed as Record<string, unknown>).status
    return typeof status === 'string' ? status : null
  } catch {
    return null
  }
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
 * 상태 변경(STATUS 프레임, #398)은 값을 렌더링에 쓰지 않는다 — `statusChangedAt` 신호만
 * 바뀌고, 화면이 그 신호로 REST를 재조회해 상태를 갱신한다(REST가 상태 표시의 정본, #399).
 *
 * @param deliveryId 구독할 배송 ID.
 * @param enabled `false`거나 `deliveryId`가 없으면 연결하지 않는다 — 배차 전(WAITING)이거나
 *                이미 종료된 배송은 서버가 404/409로 거부하므로 애초에 시도하지 않는다.
 */
export function useTrackingStream(deliveryId: number | undefined, enabled: boolean): UseTrackingStreamResult {
  const [status, setStatus] = useState<TrackingConnectionStatus>('idle')
  const [location, setLocation] = useState<LocationPing | null>(null)
  const [statusChangedAt, setStatusChangedAt] = useState<number | null>(null)
  /** 마지막으로 본 배송 상태(#449). 위치 프레임은 5초마다 오므로, 매번이 아니라
   *  값이 **달라졌을 때만** 재조회를 트리거하기 위한 기준값이다.
   *  state가 아니라 ref인 이유: 이 값이 바뀐다고 연결을 다시 맺을 이유가 없다. */
  const lastStatusRef = useRef<string | null>(null)

  useEffect(() => {
    if (!enabled || deliveryId == null) {
      setStatus('idle')
      return
    }

    setStatus('connecting')
    setLocation(null)
    setStatusChangedAt(null)
    lastStatusRef.current = null
    const source = new EventSource(trackingStreamUrl(deliveryId), { withCredentials: true })

    source.onopen = () => {
      setStatus('open')
    }

    source.onmessage = (event: MessageEvent<string>) => {
      if (parseFrameType(event.data) === 'STATUS') {
        setStatusChangedAt(Date.now())
        return
      }
      const ping = parseLocationPing(event.data)
      if (ping) {
        setLocation(ping)
      }
      // 위치 프레임에 실려 온 상태가 직전과 다르면, 그 사이 상태 전이 이벤트를 놓친 것이다(#449).
      // 값 자체는 렌더링에 쓰지 않고 재조회 신호만 울린다 — 표시할 값은 REST가 정본이다.
      // 값이 없으면(구버전 백엔드) 아무것도 하지 않아 기존 동작이 그대로 유지된다.
      const frameStatus = parseFrameStatus(event.data)
      if (frameStatus !== null && frameStatus !== lastStatusRef.current) {
        const isFirstFrame = lastStatusRef.current === null
        lastStatusRef.current = frameStatus
        // 첫 프레임은 "바뀐" 것이 아니다 — 화면은 이미 REST로 최신 상태를 읽고 진입했으므로,
        // 여기서 트리거하면 진입 직후 불필요한 재조회가 한 번 더 돈다.
        if (!isFirstFrame) {
          setStatusChangedAt(Date.now())
        }
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

  return { status, location, statusChangedAt }
}
