const POSTCODE_SDK_SRC = 'https://t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js'

let loadPromise: Promise<typeof daum> | null = null

export function loadDaumPostcode(): Promise<typeof daum> {
  if (window.daum?.Postcode) {
    return Promise.resolve(window.daum)
  }
  if (loadPromise) {
    return loadPromise
  }

  loadPromise = new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${POSTCODE_SDK_SRC}"]`)
    const script = existing ?? document.createElement('script')

    script.addEventListener('load', () => resolve(window.daum), { once: true })
    script.addEventListener('error', () => {
      loadPromise = null
      reject(new Error('주소 검색 서비스를 불러오지 못했습니다.'))
    }, { once: true })

    if (!existing) {
      script.src = POSTCODE_SDK_SRC
      script.async = true
      document.head.appendChild(script)
    }
  })

  return loadPromise
}
