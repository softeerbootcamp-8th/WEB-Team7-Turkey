import { useEffect, useRef, useState } from 'react'
import type { AddressResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { loadKakaoMaps } from '@/lib/kakaoMaps'

interface RequestRouteMapProps {
  pickup: AddressResponse | undefined
  destination: AddressResponse | undefined
  // 정보 시트가 지도 하단을 가리는 높이(px). 기본 상태(시트 올림)에서 가려지는 만큼을
  // 초기 fit 의 아래 여백으로 줘서 두 지점이 가려지지 않는 위쪽 영역에 놓이게 한다.
  bottomInsetPx?: number
}

function hasCoordinates(address: AddressResponse | undefined): address is AddressResponse & {
  latitude: number
  longitude: number
} {
  return typeof address?.latitude === 'number' && typeof address.longitude === 'number'
}

// 상세 화면 타임라인 색과 동일하게 맞춘다(픽업=파랑, 도착=선명한 빨강). 카카오 마커 이미지는
// Tailwind 클래스를 못 쓰므로 hex 를 직접 넣고, 아래 범례 점도 이 상수를 공유해 항상 핀과 일치시킨다.
const PICKUP_PIN_COLOR = '#3b82f6'
const DESTINATION_PIN_COLOR = '#ef4444'

function createPinImage(color: string): kakao.maps.MarkerImage {
  // 고객 추적 지도와 동일한 핀 모양 — 바깥 테두리(stroke) 없이 색 채운 핀 + 가운데 흰 점만.
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

export function RequestRouteMap({ pickup, destination, bottomInsetPx = 0 }: RequestRouteMapProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const markersRef = useRef<kakao.maps.Marker[]>([])
  const routeLineRef = useRef<kakao.maps.Polyline | null>(null)
  const resizeObserverRef = useRef<ResizeObserver | null>(null)
  const [mapError, setMapError] = useState<string | null>(null)

  const pickupLatitude = pickup?.latitude
  const pickupLongitude = pickup?.longitude
  const destinationLatitude = destination?.latitude
  const destinationLongitude = destination?.longitude

  useEffect(() => {
    let cancelled = false

    if (!hasCoordinates(pickup) || !hasCoordinates(destination)) {
      setMapError('출발지와 도착지 좌표가 없어 지도를 표시할 수 없습니다.')
      return
    }

    setMapError(null)
    loadKakaoMaps()
      .then(() => {
        if (cancelled || !containerRef.current) {
          return
        }

        const pickupPosition = new window.kakao.maps.LatLng(pickup.latitude, pickup.longitude)
        const destinationPosition = new window.kakao.maps.LatLng(
          destination.latitude,
          destination.longitude,
        )
        const center = new window.kakao.maps.LatLng(
          (pickup.latitude + destination.latitude) / 2,
          (pickup.longitude + destination.longitude) / 2,
        )
        const map = new window.kakao.maps.Map(containerRef.current, { center, level: 7 })
        markersRef.current = [
          new window.kakao.maps.Marker({
            position: pickupPosition,
            map,
            image: createPinImage(PICKUP_PIN_COLOR),
            title: '픽업',
          }),
          new window.kakao.maps.Marker({
            position: destinationPosition,
            map,
            image: createPinImage(DESTINATION_PIN_COLOR),
            title: '도착',
          }),
        ]
        routeLineRef.current = new window.kakao.maps.Polyline({
          path: [pickupPosition, destinationPosition],
          strokeWeight: 5,
          strokeColor: '#4A74E8',
          strokeOpacity: 0.85,
          strokeStyle: 'solid',
          map,
        })

        // 첫 렌더에 출발·도착이 모두 보이도록 두 지점을 감싸는 범위로 맞춘다(고객 추적 지도와 동일).
        // 핀 이미지(세로 40px)와 하단 "픽업/도착" 범례에 안 잘리도록 여백을 둔다.
        const bounds = new window.kakao.maps.LatLngBounds()
        bounds.extend(pickupPosition)
        bounds.extend(destinationPosition)
        // 첫 렌더에 한 번만 두 지점을 맞춘다(초기 축척 결정). 시트가 가리는 아래 영역만큼
        // 여백을 더 줘서 두 지점이 가려지지 않는 위쪽에 놓이게 한다.
        map.setBounds(bounds, 40, 40, 60 + bottomInsetPx, 40)

        // 지도 크기 자체는 고정이라 드래그 중에는 이 콜백이 돌지 않는다. 화면 회전 등으로
        // 컨테이너가 실제로 바뀔 때만 relayout 으로 타일을 다시 채운다(축척은 유지).
        const observer = new ResizeObserver(() => {
          map.relayout()
        })
        observer.observe(containerRef.current)
        resizeObserverRef.current = observer
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setMapError(error instanceof Error ? error.message : '지도를 불러오지 못했습니다.')
        }
      })

    return () => {
      cancelled = true
      resizeObserverRef.current?.disconnect()
      resizeObserverRef.current = null
      markersRef.current.forEach((marker) => marker.setMap(null))
      markersRef.current = []
      routeLineRef.current?.setMap(null)
      routeLineRef.current = null
    }
  }, [pickupLatitude, pickupLongitude, destinationLatitude, destinationLongitude])

  return (
    <div className="relative h-full w-full">
      <div ref={containerRef} className="h-full w-full" />
      {mapError && (
        <div className="absolute inset-0 flex items-center justify-center bg-surface-container-high px-6 text-center text-body-md text-secondary">
          {mapError}
        </div>
      )}
      {!mapError && (
        <div className="pointer-events-none absolute bottom-4 left-1/2 flex -translate-x-1/2 items-center gap-3 whitespace-nowrap rounded-full bg-surface-container-lowest/95 px-4 py-2 text-label-sm font-bold text-secondary shadow-md">
          <span className="flex items-center gap-1.5">
            <span
              className="h-2.5 w-2.5 rounded-full"
              style={{ backgroundColor: PICKUP_PIN_COLOR }}
              aria-hidden="true"
            />
            픽업
          </span>
          <span className="flex items-center gap-1.5">
            <span
              className="h-2.5 w-2.5 rounded-full"
              style={{ backgroundColor: DESTINATION_PIN_COLOR }}
              aria-hidden="true"
            />
            도착
          </span>
        </div>
      )}
    </div>
  )
}
