import { useEffect, useRef, useState } from 'react'
import type { AddressResponse } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { loadKakaoMaps } from '@/lib/kakaoMaps'

interface RequestRouteMapProps {
  pickup: AddressResponse | undefined
  destination: AddressResponse | undefined
}

function hasCoordinates(address: AddressResponse | undefined): address is AddressResponse & {
  latitude: number
  longitude: number
} {
  return typeof address?.latitude === 'number' && typeof address.longitude === 'number'
}

export function RequestRouteMap({ pickup, destination }: RequestRouteMapProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const markersRef = useRef<kakao.maps.Marker[]>([])
  const routeLineRef = useRef<kakao.maps.Polyline | null>(null)
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
          new window.kakao.maps.Marker({ position: pickupPosition, map }),
          new window.kakao.maps.Marker({ position: destinationPosition, map }),
        ]
        routeLineRef.current = new window.kakao.maps.Polyline({
          path: [pickupPosition, destinationPosition],
          strokeWeight: 5,
          strokeColor: '#4A74E8',
          strokeOpacity: 0.85,
          strokeStyle: 'solid',
          map,
        })
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setMapError(error instanceof Error ? error.message : '지도를 불러오지 못했습니다.')
        }
      })

    return () => {
      cancelled = true
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
        <div className="pointer-events-none absolute bottom-4 left-1/2 -translate-x-1/2 whitespace-nowrap rounded-full bg-surface-container-lowest/95 px-4 py-2 text-label-sm font-bold text-secondary shadow-md">
          픽업 · 도착 위치
        </div>
      )}
    </div>
  )
}
