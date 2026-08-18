import { Capacitor, registerPlugin } from '@capacitor/core'
import { useEffect } from 'react'

type RiderOperatingStatus = 'UNAVAILABLE' | 'AVAILABLE' | 'BUSY'

interface RiderLocationNativePlugin {
  ensurePermission(): Promise<void>
  start(options: { operatingStatus: 'BUSY'; apiBaseUrl: string }): Promise<void>
  stop(): Promise<void>
}

const RiderLocation = registerPlugin<RiderLocationNativePlugin>('RiderLocation')

/**
 * 위치 권한만 확보한다 — 포그라운드 서비스는 시작하지 않는다.
 *
 * 온라인(AVAILABLE) 전환 시 미리 호출해 권한을 받아두고, 실제 위치 전송(BUSY)은
 * startRiderLocationSender가 담당한다. 권한 확보와 서비스 시작을 분리해, "권한 거부"와
 * "아직 BUSY가 아님"이 한 Promise로 뒤섞여 화면을 잘못 막던 문제(#557)를 없앤다.
 */
// 동시 중복 호출 가드(#557): 온라인 전환 시 세션 리페치로 operatingStatus가 재도출되며 이 함수가
// 짧은 간격에 두 번 호출될 수 있는데, 네이티브 권한 요청(requestPermissionForAlias)이 겹치면 한쪽
// 호출이 거부돼 위치 권한 게이트가 잘못 뜬다(권한은 이미 허용됐는데도). 진행 중인 요청이 있으면 그
// Promise를 재사용해 네이티브 요청은 한 번만 나가고 두 호출이 같은 결과를 공유하게 한다.
let permissionRequestInFlight: Promise<void> | null = null

export async function ensureRiderLocationPermission(): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') {
    return
  }
  if (!permissionRequestInFlight) {
    permissionRequestInFlight = RiderLocation.ensurePermission().finally(() => {
      permissionRequestInFlight = null
    })
  }
  await permissionRequestInFlight
}

/**
 * 실시간 위치 전송(Android 포그라운드 서비스)을 시작한다. BUSY(배송 진행 중)에서만 호출한다 —
 * 네이티브도 BUSY만 허용한다. 권한이 아직 없으면 네이티브가 이 시점에 다시 요청한다.
 */
export async function startRiderLocationSender(): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') {
    throw new Error('Android 앱에서만 위치 송신을 시작할 수 있습니다.')
  }

  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL
  if (!apiBaseUrl) {
    throw new Error('Android 위치 서비스를 시작하려면 VITE_API_BASE_URL이 필요합니다.')
  }

  await RiderLocation.start({ operatingStatus: 'BUSY', apiBaseUrl })
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
 *
 * 상태별 동작(#557):
 *  - AVAILABLE: 위치 권한만 미리 확보(ensurePermission). 서비스는 시작하지 않는다.
 *  - BUSY: 실제 위치 전송 서비스(start)를 시작한다.
 *  - UNAVAILABLE: 서비스를 중지한다.
 *
 * `onLocationError` — 위치 권한이 실제로 거부돼 위 확보/시작이 실패했을 때 호출된다. 이전에는
 * console.error로만 남겨 위치 없이 배송이 진행될 수 있었고(#496), 권한을 필수 조건으로 만들며(#535)
 * 호출자가 이 실패를 화면에서 처리하게 했다.
 */
export function useLocationSender(
  operatingStatus: RiderOperatingStatus,
  onLocationError?: (error: unknown) => void,
): void {
  useEffect(() => {
    if (Capacitor.getPlatform() !== 'android') {
      return
    }

    if (operatingStatus === 'UNAVAILABLE') {
      void stopRiderLocationSender()
      return
    }

    if (operatingStatus === 'AVAILABLE') {
      // 온라인 전환 시 권한만 미리 확보한다(서비스 시작 없음). 실제 거부일 때만 게이트가 뜬다.
      void ensureRiderLocationPermission().catch((error: unknown) => {
        console.error('위치 권한을 확보하지 못했습니다.', error)
        onLocationError?.(error)
      })
      return
    }

    // BUSY — 실제 위치 전송 서비스 시작
    void startRiderLocationSender().catch((error: unknown) => {
      console.error('Android 위치 서비스를 시작하지 못했습니다.', error)
      onLocationError?.(error)
    })
    // onLocationError는 트리거로 삼지 않는다 — operatingStatus가 바뀔 때만 네이티브 서비스를
    // 다시 시작해야 하고, 콜백 정체성이 매 렌더 바뀌어도 재시작 루프가 생기면 안 된다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
