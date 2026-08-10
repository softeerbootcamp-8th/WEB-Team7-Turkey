const SDK_SRC_PREFIX = 'https://dapi.kakao.com/v2/maps/sdk.js'
const SDK_LOAD_TIMEOUT_MS = 10_000
const SDK_READY_POLL_MS = 50

let loadPromise: Promise<typeof kakao> | null = null

/**
 * 카카오맵 JavaScript SDK를 1회만 로드하고, `kakao.maps` 네임스페이스가 실제로 준비된
 * 뒤에 resolve한다.
 *
 * `autoload=false`로 동적 스크립트의 `document.write()` 자동 로딩을 피하고,
 * `kakao.maps.load` 함수가 실제로 준비된 뒤 서브모듈을 초기화한다. 느린 WebView에서는
 * script 태그가 먼저 발견되고 SDK 실행은 아직 끝나지 않을 수 있어 함수 준비도 기다린다.
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
    const startedAt = Date.now()

    function initializeWhenReady() {
      if (Date.now() - startedAt >= SDK_LOAD_TIMEOUT_MS) {
        loadPromise = null
        reject(new Error('카카오맵 주소 변환 서비스를 초기화하지 못했습니다.'))
        return
      }

      if (typeof window.kakao?.maps?.load !== 'function') {
        window.setTimeout(initializeWhenReady, SDK_READY_POLL_MS)
        return
      }

      window.kakao.maps.load(() => {
        if (typeof window.kakao?.maps?.services?.Geocoder !== 'function') {
          loadPromise = null
          reject(new Error('카카오맵 주소 변환 모듈을 불러오지 못했습니다.'))
          return
        }
        resolve(window.kakao)
      })
    }

    const existing = document.querySelector<HTMLScriptElement>(`script[src^="${SDK_SRC_PREFIX}"]`)
    if (existing) {
      initializeWhenReady()
      return
    }

    const script = document.createElement('script')
    script.src = `${SDK_SRC_PREFIX}?appkey=${appKey}&autoload=false&libraries=services`
    script.async = true
    script.onload = initializeWhenReady
    script.onerror = () => {
      loadPromise = null
      reject(new Error('카카오맵 SDK 로드에 실패했습니다.'))
    }
    document.head.appendChild(script)
  })

  return loadPromise
}
