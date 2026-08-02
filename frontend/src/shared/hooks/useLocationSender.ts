import { Capacitor, registerPlugin } from '@capacitor/core'
import { useEffect } from 'react'

type RiderOperatingStatus = 'UNAVAILABLE' | 'AVAILABLE' | 'BUSY'

interface RiderLocationNativePlugin {
  start(options: { operatingStatus: 'AVAILABLE' | 'BUSY'; apiBaseUrl: string }): Promise<void>
  stop(): Promise<void>
}

const RiderLocation = registerPlugin<RiderLocationNativePlugin>('RiderLocation')

export async function startRiderLocationSender(
  operatingStatus: 'AVAILABLE' | 'BUSY' = 'AVAILABLE',
): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') {
    throw new Error('Android 앱에서만 위치 송신을 시작할 수 있습니다.')
  }

  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL
  if (!apiBaseUrl) {
    throw new Error('Android 위치 서비스를 시작하려면 VITE_API_BASE_URL이 필요합니다.')
  }

  await RiderLocation.start({ operatingStatus, apiBaseUrl })
}

export async function stopRiderLocationSender(): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') {
    return
  }
  await RiderLocation.stop()
}

/**
 * Android 네이티브 위치 서비스를 운행 상태와 동기화한다.
 *
 * 위치 수집·주기 제어·HTTP 전송은 모두 Android Foreground Service가 담당한다. WebView는 서비스에
 * 시작·상태 변경·중지 명령만 전달하므로 앱이 백그라운드로 내려가도 위치 전송이 계속된다.
 */
export function useLocationSender(operatingStatus: RiderOperatingStatus): void {
  useEffect(() => {
    if (Capacitor.getPlatform() !== 'android') {
      return
    }

    if (operatingStatus === 'UNAVAILABLE') {
      void stopRiderLocationSender()
      return
    }

    void startRiderLocationSender(operatingStatus).catch((error: unknown) => {
      console.error('Android 위치 서비스를 시작하지 못했습니다.', error)
    })
  }, [operatingStatus])

  // 로그아웃 등으로 라이더 인증 영역 자체가 사라질 때만 네이티브 서비스를 종료한다.
  useEffect(
    () => () => {
      if (Capacitor.getPlatform() === 'android') {
        void stopRiderLocationSender()
      }
    },
    [],
  )
}
