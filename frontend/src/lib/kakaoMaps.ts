const SDK_SRC_PREFIX = '//dapi.kakao.com/v2/maps/sdk.js'

let loadPromise: Promise<typeof kakao> | null = null

/**
 * 카카오맵 JavaScript SDK를 1회만 로드하고, `kakao.maps` 네임스페이스가 실제로 준비된
 * 뒤에 resolve한다.
 *
 * `autoload=false` + `kakao.maps.load(callback)` 을 쓰는 이유: SDK 스크립트 자체는
 * 빠르게 로드되지만 지도 서브모듈은 그 뒤에 비동기로 마저 로드된다. `autoload`(기본값
 * true)를 쓰면 그 완료 시점을 알 방법이 없어, 컴포넌트가 스크립트 `onload` 직후 아직
 * 준비되지 않은 `kakao.maps.Map`을 참조하는 경쟁이 생긴다.
 *
 * 모듈 스코프 변수로 1회만 로드하는 이유: 이 로더를 여러 컴포넌트(지도가 여러 화면에
 * 생길 수 있음)가 각자 부르면 `<script>` 태그가 중복 삽입되고, 카카오 SDK는 중복
 * 로드 시 콘솔 경고를 내며 이후 동작이 불안정해진다.
 */
export function loadKakaoMaps(): Promise<typeof kakao> {
  if (loadPromise) {
    return loadPromise
  }

  const appKey = import.meta.env.VITE_KAKAO_MAP_KEY
  if (!appKey) {
    return Promise.reject(
      new Error('VITE_KAKAO_MAP_KEY가 설정되지 않았습니다. frontend/.env.local을 확인하세요.'),
    )
  }

  loadPromise = new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src^="${SDK_SRC_PREFIX}"]`)
    if (existing) {
      window.kakao.maps.load(() => resolve(window.kakao))
      return
    }

    const script = document.createElement('script')
    script.src = `${SDK_SRC_PREFIX}?appkey=${appKey}&autoload=false&libraries=services`
    script.async = true
    script.onload = () => {
      window.kakao.maps.load(() => resolve(window.kakao))
    }
    script.onerror = () => {
      loadPromise = null
      reject(new Error('카카오맵 SDK 로드에 실패했습니다.'))
    }
    document.head.appendChild(script)
  })

  return loadPromise
}
