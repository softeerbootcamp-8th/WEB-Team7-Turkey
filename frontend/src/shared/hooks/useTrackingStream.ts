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

  useEffect(() => {
    if (!enabled || deliveryId == null) {
      setStatus('idle')
      return
    }

    setStatus('connecting')
    setLocation(null)
    setStatusChangedAt(null)
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
    }

    source.onerror = () => {
      // 브라우저 EventSource는 서버가 200이 아닌 응답을 준 게 아니라면 자동으로 재연결을
      // 시도한다(readyState가 CONNECTING으로 돌아감). CLOSED면 브라우저가 재시도를 포기한
      // 것이다(예: 401/404/409 응답).
      const rejected = source.readyState === EventSource.CLOSED
      setStatus(rejected ? 'error' : 'reconnecting')

      // 재연결이 서버에 의해 거부됐다는 것은 그 배송이 더 이상 구독 대상이 아니라는 뜻이다 —
      // 배송이 완료·취소되면 서버가 연결을 닫고(#450), 뒤이은 재연결이 409로 거부된다.
      // 그런데 EventSource는 상태코드도 본문도 스크립트에 노출하지 않으므로, 아는 것은
      // "영구 실패했다"뿐이다. 그래서 읽을 수 있는 채널(REST)로 다시 물어본다.
      //
      // 단순히 연결이 끊긴 경우(CONNECTING)에는 트리거하지 않는다 — 브라우저가 알아서
      // 재연결하고, 그 재연결이 성공하면 아직 진행 중인 배송이라는 뜻이다.
      if (rejected) {
        setStatusChangedAt(Date.now())
      }
    }

    return () => {
      source.close()
    }
  }, [deliveryId, enabled])

  return { status, location, statusChangedAt }
}
