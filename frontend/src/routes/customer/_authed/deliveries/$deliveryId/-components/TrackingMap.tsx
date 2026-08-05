import { useEffect, useRef, useState } from 'react'
import riderMarkerSrc from '@/assets/rider-marker-yamaha-r3.svg'
import { loadKakaoMaps } from '@/lib/kakaoMaps'
import type { LocationPing } from '@/shared/hooks/useTrackingStream'

interface TrackingMapProps {
  location: LocationPing | null
}

/** 위치를 아직 못 받았을 때 지도 초기 중심(서울시청) — 실제 위치가 오면 바로 그쪽으로 이동한다. */
const DEFAULT_CENTER = { latitude: 37.5665, longitude: 126.978 }

/**
 * 라이더 마커를 카카오 기본 핀 대신 오토바이 이미지로 그린다.
 *
 * 이미지 원본은 128×116 이고 아래 꼭짓점(64, 102)이 좌표에 닿는 지점이다. 화면에는
 * 64×58 로 축소해 그리므로 offset 도 같은 비율로 줄여야 한다 — 64×(64/128)=32,
 * 58×(102/116)≈51. offset 을 빼먹으면 이미지 중앙이 좌표에 놓여 마커가 실제 위치보다
 * 위쪽을 가리킨다.
 *
 * 실제 사진으로 바꾸려면 같은 경로의 파일만 교체하고 아래 크기·offset 을 그 이미지 기준으로
 * 다시 계산하면 된다(코드 수정은 이 상수들뿐).
 */
const RIDER_MARKER = {
  width: 64,
  height: 58,
  offsetX: 32,
  offsetY: 51,
} as const

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
      const markerImage = new window.kakao.maps.MarkerImage(
        riderMarkerSrc,
        new window.kakao.maps.Size(RIDER_MARKER.width, RIDER_MARKER.height),
        { offset: new window.kakao.maps.Point(RIDER_MARKER.offsetX, RIDER_MARKER.offsetY) },
      )
      markerRef.current = new window.kakao.maps.Marker({
        position,
        map,
        image: markerImage,
        title: '라이더 위치',
      })
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
