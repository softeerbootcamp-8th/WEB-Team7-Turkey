import { useEffect, useRef, useState } from 'react'
import riderMarkerSrc from '@/assets/yamaha.png'
import riderMarkerFlippedSrc from '@/assets/yamaha-flipped.png'
import { loadKakaoMaps } from '@/lib/kakaoMaps'
import type { LocationPing } from '@/shared/hooks/useTrackingStream'

interface TrackingMapProps {
  location: LocationPing | null
}

/** 위치를 아직 못 받았을 때 지도 초기 중심(서울시청) — 실제 위치가 오면 바로 그쪽으로 이동한다. */
const DEFAULT_CENTER = { latitude: 37.5665, longitude: 126.978 }

/**
 * 라이더 마커를 카카오 기본 핀 대신 오토바이 사진으로 그린다.
 *
 * 원본은 633×487 이고, 핀처럼 뾰족한 꼭짓점이 없는 일반 사진이라 **바퀴가 지면에 닿는
 * 아래-가운데**를 좌표에 맞춘다. 불투명 픽셀 경계를 실측한 값이 기준이다 — 가로 중심은
 * 폭의 0.500, 아래 끝은 높이의 0.982 지점이다(오토바이가 프레임을 거의 꽉 채운다).
 *
 * 화면에는 원본 비율(1.300)을 유지해 64×49 로 축소해 그리므로 offset 도 같은 비율로
 * 줄인다 — 64×0.500=32, 49×0.982≈48. offset 을 빼먹으면 이미지 중앙이 좌표에 놓여
 * 마커가 실제 위치보다 위쪽을 가리킨다. `yamaha-flipped.png`는 `yamaha.png`를 가로로
 * 뒤집기만 한 것이라 폭 중심(0.500)이 그대로 유지돼 offsetX 를 공유한다.
 *
 * 다른 이미지로 바꾸려면 **배경이 투명해야 한다**(불투명 배경이면 지도 위에 사각형으로
 * 보인다). 그다음 위와 같이 불투명 영역의 가로 중심·아래 끝 비율을 재서 아래 상수만
 * 다시 계산하면 된다.
 */
const RIDER_MARKER = {
  width: 64,
  height: 49,
  offsetX: 32,
  offsetY: 48,
} as const

type Facing = 'right' | 'left'

/**
 * `yamaha.png` 원본은 정지 사진이라 임의 각도로 돌리면(예: 90도) 오토바이가 넘어진 것처럼
 * 보인다 — 위에서 내려다본 아이콘이 아니라 옆에서 찍은 사진이기 때문이다. 그래서 방향
 * 표현은 **좌우 반전 두 가지 상태로만** 근사한다. 원본은 정면(헤드라이트)이 오른쪽을
 * 향하므로 `right` = 원본, `left` = 좌우 반전(`yamaha-flipped.png`).
 */
const MARKER_IMAGE_SRC: Record<Facing, string> = {
  right: riderMarkerSrc,
  left: riderMarkerFlippedSrc,
}

/**
 * 위경도 delta 가 이 값보다 작으면 방향을 바꾸지 않고 직전 상태를 유지한다. 라이더가
 * 정지한 채 주기적으로 재전송하는 핑(위치 정책 §7, 정지 시 120초 강제 전송)이나 GPS
 * 지터로 생기는 미세한 delta 가 매번 마커를 좌우로 떨게 만드는 것을 막는 dead zone이다.
 * 0.0001도는 적도 기준 약 11m — 라이더 클라이언트의 최소 이동 거리 필터(20m)보다 작아
 * 실제 이동은 걸러내지 않는다. 남북 이동처럼 경도 변화가 거의 없는 경우도 이 dead zone에
 * 걸려 직전 방향을 유지하는데, 애초에 측면 사진이라 남북 방향을 표현할 수 없으므로 의도된
 * 동작이다.
 */
const LONGITUDE_DEAD_ZONE = 0.0001

function facingFromMovement(prev: LocationPing | null, next: LocationPing, current: Facing): Facing {
  if (!prev) {
    return current
  }
  const deltaLongitude = next.longitude - prev.longitude
  if (deltaLongitude > LONGITUDE_DEAD_ZONE) {
    return 'right'
  }
  if (deltaLongitude < -LONGITUDE_DEAD_ZONE) {
    return 'left'
  }
  return current
}

function buildMarkerImage(facing: Facing): kakao.maps.MarkerImage {
  return new window.kakao.maps.MarkerImage(
    MARKER_IMAGE_SRC[facing],
    new window.kakao.maps.Size(RIDER_MARKER.width, RIDER_MARKER.height),
    { offset: new window.kakao.maps.Point(RIDER_MARKER.offsetX, RIDER_MARKER.offsetY) },
  )
}

/**
 * 라이더 실시간 위치를 카카오맵에 표시한다. `location`이 없으면(#209 예외 흐름 —
 * 배차 직후라 라이더가 아직 위치를 안 보냈거나 재연결 중) 지도 위에 안내 문구를 겹쳐 보여주고,
 * 지도 자체는 기본 중심에 그대로 둔다.
 */
export function TrackingMap({ location }: TrackingMapProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<kakao.maps.Map | null>(null)
  const markerRef = useRef<kakao.maps.Marker | null>(null)
  const previousLocationRef = useRef<LocationPing | null>(null)
  const facingRef = useRef<Facing>('right')
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
    const nextFacing = facingFromMovement(previousLocationRef.current, location, facingRef.current)
    const facingChanged = nextFacing !== facingRef.current
    facingRef.current = nextFacing
    previousLocationRef.current = location

    if (markerRef.current) {
      markerRef.current.setPosition(position)
      if (facingChanged) {
        markerRef.current.setImage(buildMarkerImage(nextFacing))
      }
    } else {
      markerRef.current = new window.kakao.maps.Marker({
        position,
        map,
        image: buildMarkerImage(nextFacing),
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
