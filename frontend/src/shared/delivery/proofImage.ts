import { Capacitor } from '@capacitor/core'

/**
 * 배송 완료 인증 사진의 표시 소스를 해석한다. 고객 배송 상세와 라이더 운행 기록 상세가 공유한다
 * (같은 `delivery_proof.proof_value` 를 화면에 그린다).
 *
 * proofValue 는 백엔드 저장소가 해석한 값이다:
 * - S3/CDN URL·웹 상대 URL → 그대로 `<img src>` 로 쓴다.
 * - 로컬 절대경로(LocalRiderDeliveryProofStorage) → 네이티브 WebView(Capacitor) 또는 로컬 Vite
 *   개발 서버(`/@fs`)에서만 변환해 띄운다. 배포 웹에서는 브라우저 보안상 로컬 파일을 읽을 수 없어
 *   null 을 반환한다(호출자가 대체 UI 를 그린다).
 */
export type ProofImageEnvironment = 'development-web' | 'native' | 'production-web'

function currentProofImageEnvironment(): ProofImageEnvironment {
  if (Capacitor.isNativePlatform()) {
    return 'native'
  }
  return import.meta.env.DEV ? 'development-web' : 'production-web'
}

function localPathFromProofValue(value: string): string | null {
  if (/^[A-Za-z]:[\\/]/.test(value)) {
    return value.replaceAll('\\', '/')
  }
  if (/^\/(Users|home|private|tmp|var)\//.test(value)) {
    return value
  }
  if (value.startsWith('file://')) {
    try {
      return decodeURIComponent(new URL(value).pathname)
    } catch {
      return null
    }
  }
  return null
}

export function resolveProofImageSource(
  proofValue: string | null | undefined,
  environment: ProofImageEnvironment = currentProofImageEnvironment(),
): string | null {
  const value = proofValue?.trim()
  if (!value) {
    return null
  }

  const localPath = localPathFromProofValue(value)
  if (!localPath) {
    return value
  }
  if (environment === 'native') {
    return Capacitor.convertFileSrc(localPath)
  }
  if (environment === 'development-web') {
    return `/@fs${encodeURI(localPath)}`
  }
  return null
}
