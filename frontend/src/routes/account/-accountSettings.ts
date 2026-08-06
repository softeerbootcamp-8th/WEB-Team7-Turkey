import { isAxiosError } from 'axios'
import type { ApiResponseVoid } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

/**
 * 로그아웃 실패를 화면이 어떻게 다뤄야 하는지로 분류한다(#222).
 *
 * 실패를 한 덩어리로 보면 안 된다 — 서버가 **명시적으로 거부한 것**과 **성사 여부를 모르는 것**은
 * 대응이 정반대다. 전자는 세션이 확실히 남아 있으므로 화면에 머물러야 하고, 후자는 남았는지 알 수
 * 없으므로 이 기기의 캐시만이라도 비워야 다음 사용자에게 이전 사용자 데이터가 보이지 않는다.
 */
export type LogoutFailure =
  /** 서버에 세션이 이미 없다(401). 원하던 결과와 같으므로 성공과 똑같이 처리한다. */
  | { kind: 'already-signed-out' }
  /** 서버가 거부했다(409, 라이더 BUSY). 세션은 그대로이므로 캐시를 비우면 안 된다. */
  | { kind: 'blocked'; message: string }
  /** 성사 여부 불명(네트워크·5xx). 캐시는 정리하되 서버 세션이 남았을 수 있음을 알린다. */
  | { kind: 'local-only'; message: string }

/** 화면에 알림으로 띄워야 하는 실패. `already-signed-out` 은 성공 경로로 흡수되므로 빠진다. */
export type LogoutNotice = Extract<LogoutFailure, { message: string }>

const BLOCKED_FALLBACK = '지금은 로그아웃할 수 없습니다.'
const NETWORK_MESSAGE = '서버에 연결하지 못해 로그아웃을 확인하지 못했습니다. 이 기기에서는 로그아웃 처리했습니다.'
const SERVER_FALLBACK = '로그아웃 요청이 실패했습니다. 이 기기에서는 로그아웃 처리했습니다.'

/** 서버가 `ApiResponse.message` 로 준 사유를 우선 쓰고, 없으면 기본 문구로 떨어진다. */
function serverMessage(error: unknown): string | undefined {
  if (!isAxiosError<ApiResponseVoid>(error)) {
    return undefined
  }
  return error.response?.data?.message || undefined
}

export function resolveLogoutFailure(error: unknown): LogoutFailure {
  if (!isAxiosError<ApiResponseVoid>(error)) {
    return { kind: 'local-only', message: SERVER_FALLBACK }
  }

  // 응답 자체가 없으면 요청이 서버에 닿았는지도 알 수 없다.
  if (!error.response) {
    return { kind: 'local-only', message: NETWORK_MESSAGE }
  }

  // 로그아웃 경로는 인터셉터에 등록돼 있지 않아 서버가 401 을 주지 않지만, 프록시·게이트웨이가
  // 낼 수는 있다. 어느 쪽이든 "세션이 없다"는 뜻이라 오류로 보여줄 이유가 없다(#222 예외 흐름).
  if (error.response.status === 401) {
    return { kind: 'already-signed-out' }
  }

  // 409 는 서버가 상태를 근거로 거부한 것이다(라이더 BUSY). 세션이 살아 있음이 확정이다.
  if (error.response.status === 409) {
    return { kind: 'blocked', message: serverMessage(error) ?? BLOCKED_FALLBACK }
  }

  return { kind: 'local-only', message: serverMessage(error) ?? SERVER_FALLBACK }
}
