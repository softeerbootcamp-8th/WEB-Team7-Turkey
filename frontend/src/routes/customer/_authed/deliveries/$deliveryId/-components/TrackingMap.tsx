import { useEffect, useRef, useState } from 'react'
import { loadKakaoMaps } from '@/lib/kakaoMaps'
import type { LocationPing } from '@/shared/hooks/useTrackingStream'
import type { AddressResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

interface TrackingMapProps {
  location: LocationPing | null
  pickup?: AddressResponse
  destination?: AddressResponse
  isTrackable?: boolean
}

/** 위치를 아직 못 받았을 때 지도 초기 중심(서울시청) — 실제 위치가 오면 바로 그쪽으로 이동한다. */
const DEFAULT_CENTER = { latitude: 37.5665, longitude: 126.978 }

function hasCoordinates(address: AddressResponse | undefined): address is AddressResponse & {
  latitude: number
  longitude: number
} {
  return typeof address?.latitude === 'number' && typeof address.longitude === 'number'
}

/** 픽업(파란색)·도착지(빨간색) 마커를 구분하는 핀 이미지 — 화면 하단 주문 상세의 색상과 맞춘다. */
function createPinMarkerImage(color: string): kakao.maps.MarkerImage {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="32" height="40" viewBox="0 0 32 40">
    <path d="M16 0C7.163 0 0 7.163 0 16c0 11 16 24 16 24s16-13 16-24C32 7.163 24.837 0 16 0z" fill="${color}"/>
    <circle cx="16" cy="16" r="6" fill="white"/>
  </svg>`
  return new window.kakao.maps.MarkerImage(
    `data:image/svg+xml;base64,${btoa(svg)}`,
    new window.kakao.maps.Size(32, 40),
    { offset: new window.kakao.maps.Point(16, 40) },
  )
}

const PICKUP_MARKER_COLOR = '#3B82F6'
const DESTINATION_MARKER_COLOR = '#EF4444'

/**
 * 라이더 실시간 위치를 카카오맵에 표시한다. `location`이 없으면(#209 예외 흐름 —
 * 배차 직후라 라이더가 아직 위치를 안 보냈거나 재연결 중) 지도 위에 안내 문구를 겹쳐 보여주고,
 * 지도 자체는 기본 중심에 그대로 둔다.
 */
export function TrackingMap({ location, pickup, destination, isTrackable = false }: TrackingMapProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<kakao.maps.Map | null>(null)
  const markerRef = useRef<kakao.maps.Marker | null>(null)
  const pickupMarkerRef = useRef<kakao.maps.Marker | null>(null)
  const destinationMarkerRef = useRef<kakao.maps.Marker | null>(null)
  const routeLineRef = useRef<kakao.maps.Polyline | null>(null)
  const [sdkError, setSdkError] = useState<string | null>(null)
  const [mapReady, setMapReady] = useState(false)

  useEffect(() => {
    let cancelled = false
    loadKakaoMaps()
      .then(() => {
        if (cancelled || !containerRef.current) {
          return
        }
        const center = new window.kakao.maps.LatLng(DEFAULT_CENTER.latitude, DEFAULT_CENTER.longitude)
        mapRef.current = new window.kakao.maps.Map(containerRef.current, { center, level: 4 })
        setMapReady(true)
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
  }, [location, mapReady])

  useEffect(() => {
    const map = mapRef.current
    if (!map || !hasCoordinates(pickup) || !hasCoordinates(destination)) {
      return
    }

    const pickupPosition = new window.kakao.maps.LatLng(pickup.latitude, pickup.longitude)
    const destinationPosition = new window.kakao.maps.LatLng(destination.latitude, destination.longitude)

    pickupMarkerRef.current = new window.kakao.maps.Marker({
      position: pickupPosition,
      map,
      image: createPinMarkerImage(PICKUP_MARKER_COLOR),
    })
    destinationMarkerRef.current = new window.kakao.maps.Marker({
      position: destinationPosition,
      map,
      image: createPinMarkerImage(DESTINATION_MARKER_COLOR),
    })
    routeLineRef.current = new window.kakao.maps.Polyline({
      path: [pickupPosition, destinationPosition],
      strokeWeight: 5,
      strokeColor: '#4A74E8',
      strokeOpacity: 0.85,
      strokeStyle: 'solid',
      map,
    })
    const bounds = new window.kakao.maps.LatLngBounds()
    bounds.extend(pickupPosition)
    bounds.extend(destinationPosition)
    // 핀 이미지(세로 40px)와 하단 "픽업 · 도착 위치" 라벨에 안 잘리도록 여백을 둔다.
    map.setBounds(bounds, 40, 40, 60, 40)

    return () => {
      pickupMarkerRef.current?.setMap(null)
      pickupMarkerRef.current = null
      destinationMarkerRef.current?.setMap(null)
      destinationMarkerRef.current = null
      routeLineRef.current?.setMap(null)
      routeLineRef.current = null
    }
  }, [pickup?.latitude, pickup?.longitude, destination?.latitude, destination?.longitude, mapReady])

  const showRoutePins = hasCoordinates(pickup) && hasCoordinates(destination)

  return (
    <div className="w-full h-full relative">
      {/* isolate: 카카오맵 내부 레이어(폴리라인 SVG 등)가 z-index 를 갖고 있어, 이 컨테이너에
          별도 포지셔닝 컨텍스트가 없으면 아래 오버레이(안내 문구·마커 라벨)를 덮어버린다. */}
      <div ref={containerRef} className="w-full h-full isolate" />
      {sdkError && (
        <div className="absolute inset-0 flex items-center justify-center bg-gray-200 text-sm text-gray-500 px-4 text-center">
          {sdkError}
        </div>
      )}
      {!sdkError && isTrackable && !location && (
        <div className="absolute inset-0 flex items-center justify-center bg-gray-200/80 text-sm text-gray-500">
          라이더 위치를 기다리는 중입니다
        </div>
      )}
      {!sdkError && showRoutePins && (
        <div className="pointer-events-none absolute bottom-4 left-1/2 -translate-x-1/2 whitespace-nowrap rounded-full bg-surface-container-lowest/95 px-4 py-2 text-label-sm font-bold text-secondary shadow-md">
          픽업 · 도착 위치
        </div>
      )}
    </div>
  )
}
