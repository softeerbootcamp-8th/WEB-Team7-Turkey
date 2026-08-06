const SDK_SRC_PREFIX = 'https://dapi.kakao.com/v2/maps/sdk.js'
const SDK_LOAD_TIMEOUT_MS = 10_000
const SDK_READY_POLL_MS = 50

let loadPromise: Promise<typeof kakao> | null = null

/**
 * 카카오맵 JavaScript SDK를 1회만 로드하고, `kakao.maps` 네임스페이스가 실제로 준비된
 * 뒤에 resolve한다.
 *
 * 브라우저와 Capacitor WebView 모두에서 실제 사용 API(`services.Geocoder`)가 준비된
 * 시점을 기준으로 resolve한다. WebView에서는 SDK 스크립트가 로드돼도 `maps.load`가
 * 제공되지 않는 경우가 있으므로 그 함수에는 의존하지 않는다.
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

    function waitUntilReady() {
      if (typeof window.kakao?.maps?.services?.Geocoder === 'function') {
        resolve(window.kakao)
        return
      }

      if (Date.now() - startedAt >= SDK_LOAD_TIMEOUT_MS) {
        loadPromise = null
        reject(new Error(
          `카카오맵 주소 변환 서비스를 초기화하지 못했습니다. `
          + `Kakao Developers의 JavaScript SDK 도메인에 ${window.location.origin}을 등록해 주세요.`,
        ))
        return
      }

      window.setTimeout(waitUntilReady, SDK_READY_POLL_MS)
    }

    const existing = document.querySelector<HTMLScriptElement>(`script[src^="${SDK_SRC_PREFIX}"]`)
    if (existing) {
      waitUntilReady()
      return
    }

    const script = document.createElement('script')
    script.src = `${SDK_SRC_PREFIX}?appkey=${appKey}&libraries=services`
    script.async = true
    script.onload = waitUntilReady
    script.onerror = () => {
      loadPromise = null
      reject(new Error('카카오맵 SDK 로드에 실패했습니다.'))
    }
    document.head.appendChild(script)
  })

  return loadPromise
}
