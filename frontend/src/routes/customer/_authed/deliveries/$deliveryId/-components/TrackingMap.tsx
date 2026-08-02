import { useEffect, useRef, useState } from 'react'
import { loadKakaoMaps } from '@/lib/kakaoMaps'
import type { LocationPing } from '@/shared/hooks/useTrackingStream'

interface TrackingMapProps {
  location: LocationPing | null
}

/** 위치를 아직 못 받았을 때 지도 초기 중심(서울시청) — 실제 위치가 오면 바로 그쪽으로 이동한다. */
const DEFAULT_CENTER = { latitude: 37.5665, longitude: 126.978 }

/**
 * 라이더 실시간 위치를 카카오맵에 표시한다. `location`이 없으면(#209 예외 흐름 —
 * 배차 직후라 라이더가 아직 위치를 안 보냈거나 재연결 중) 지도 위에 안내 문구를 겹쳐 보여주고,
 * 지도 자체는 기본 중심에 그대로 둔다.
 */
export function TrackingMap({ location }: TrackingMapProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<kakao.maps.Map | null>(null)
  const markerRef = useRef<kakao.maps.Marker | null>(null)
  const [sdkError, setSdkError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    loadKakaoMaps()
      .then(() => {
        if (cancelled || !containerRef.current) {
          return
        }
        const center = new window.kakao.maps.LatLng(DEFAULT_CENTER.latitude, DEFAULT_CENTER.longitude)
        mapRef.current = new window.kakao.maps.Map(containerRef.current, { center, level: 4 })
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setSdkError(error instanceof Error ? error.message : '지도를 불러오지 못했습니다.')
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    if (!map || !location) {
      return
    }

    const position = new window.kakao.maps.LatLng(location.latitude, location.longitude)
    if (markerRef.current) {
      markerRef.current.setPosition(position)
    } else {
      markerRef.current = new window.kakao.maps.Marker({ position, map })
    }
    map.panTo(position)
  }, [location])

  return (
    <div className="w-full h-full relative">
      <div ref={containerRef} className="w-full h-full" />
      {sdkError && (
        <div className="absolute inset-0 flex items-center justify-center bg-gray-200 text-sm text-gray-500 px-4 text-center">
          {sdkError}
        </div>
      )}
      {!sdkError && !location && (
        <div className="absolute inset-0 flex items-center justify-center bg-gray-200/80 text-sm text-gray-500">
          라이더 위치를 기다리는 중입니다
        </div>
      )}
    </div>
  )
}
