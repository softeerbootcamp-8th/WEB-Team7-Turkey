export {}

/**
 * 카카오맵 JavaScript SDK 최소 타입 선언. 공식 타입 패키지를 추가하는 대신, 이 프로젝트가
 * 실제로 쓰는 만큼만(지도 생성·중심 이동·마커) 선언한다 — 새 의존성 없이 타입 안전성만 확보.
 */
declare global {
  namespace kakao {
    namespace maps {
      class LatLng {
        constructor(latitude: number, longitude: number)
      }

      class Map {
        constructor(container: HTMLElement, options: { center: LatLng; level: number })
        setCenter(position: LatLng): void
        panTo(position: LatLng): void
      }

      class Marker {
        constructor(options: { position: LatLng; map?: Map })
        setPosition(position: LatLng): void
        setMap(map: Map | null): void
      }

      class Polyline {
        constructor(options: {
          path: LatLng[]
          strokeWeight?: number
          strokeColor?: string
          strokeOpacity?: number
          strokeStyle?: string
          map?: Map
        })
        setMap(map: Map | null): void
      }

      function load(callback: () => void): void
    }
  }

  interface Window {
    kakao: typeof kakao
  }
}
