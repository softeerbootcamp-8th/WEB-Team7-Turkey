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
        setLevel(level: number): void
        setBounds(
          bounds: LatLngBounds,
          paddingTop?: number,
          paddingRight?: number,
          paddingBottom?: number,
          paddingLeft?: number,
        ): void
        panTo(position: LatLng): void
      }

      class LatLngBounds {
        constructor()
        extend(latlng: LatLng): void
      }

      class Size {
        constructor(width: number, height: number)
      }

      class Point {
        constructor(x: number, y: number)
      }

      /**
       * 기본 핀 대신 쓸 마커 이미지. `options.offset`은 이미지 안에서 실제 좌표에 닿는
       * 지점(픽셀)이며, 지정하지 않으면 이미지 중앙이 좌표에 놓여 마커가 위로 뜬 것처럼 보인다.
       */
      class MarkerImage {
        constructor(src: string, size: Size, options?: { offset?: Point })
      }

      class Marker {
        constructor(options: { position: LatLng; map?: Map; image?: MarkerImage; title?: string })
        setPosition(position: LatLng): void
        setImage(image: MarkerImage): void
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

      namespace services {
        const Status: { OK: string }

        type AddressSearchResult = {
          x: string
          y: string
        }

        class Geocoder {
          addressSearch(
            address: string,
            callback: (results: AddressSearchResult[], status: string) => void,
          ): void
        }
      }

      function load(callback: () => void): void
    }
  }

  interface Window {
    kakao: typeof kakao
    daum: typeof daum
  }

  namespace daum {
    type PostcodeData = {
      address: string
      roadAddress: string
      zonecode: string
    }

    class Postcode {
      constructor(options: {
        oncomplete: (data: PostcodeData) => void
        onclose?: () => void
      })
      open(): void
    }
  }
}
