export type SessionRole = 'CUSTOMER' | 'RIDER'

export const SESSION_ROLE_STORAGE_KEY = 'turkey.session.role'

type RoleStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

let memoryRole: SessionRole | null = null

function getLocalStorage(): RoleStorage | null {
  if (typeof window === 'undefined') {
    return null
  }

  try {
    return window.localStorage
  } catch {
    return null
  }
}

function isSessionRole(value: string | null): value is SessionRole {
  return value === 'CUSTOMER' || value === 'RIDER'
}

/**
 * 역할은 인증 정보가 아니라 어떤 역할별 세션 API를 호출할지 고르는 힌트다.
 * 실제 인증·인가는 HttpOnly 세션 쿠키와 서버 검증이 계속 담당한다.
 */
export function readStoredSessionRole(storage: RoleStorage | null = getLocalStorage()): SessionRole | null {
  if (!storage) {
    return memoryRole
  }

  try {
    const value = storage.getItem(SESSION_ROLE_STORAGE_KEY)
    if (isSessionRole(value)) {
      memoryRole = value
      return value
    }

    memoryRole = null
    if (value !== null) {
      storage.removeItem(SESSION_ROLE_STORAGE_KEY)
    }
    return null
  } catch {
    return memoryRole
  }
}

export function storeSessionRole(role: SessionRole, storage: RoleStorage | null = getLocalStorage()): void {
  memoryRole = role

  try {
    storage?.setItem(SESSION_ROLE_STORAGE_KEY, role)
  } catch {
    // 저장소 접근이 막힌 환경에서는 현재 탭의 메모리 힌트만 사용한다.
  }
}

export function clearStoredSessionRole(storage: RoleStorage | null = getLocalStorage()): void {
  memoryRole = null

  try {
    storage?.removeItem(SESSION_ROLE_STORAGE_KEY)
  } catch {
    // 서버 세션 정리와 라우터 재평가는 저장소 오류와 무관하게 계속 진행한다.
  }
}
