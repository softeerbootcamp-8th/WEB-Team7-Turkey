import { beforeEach, describe, expect, it } from 'vitest'
import {
  clearStoredSessionRole,
  readStoredSessionRole,
  SESSION_ROLE_STORAGE_KEY,
  storeSessionRole,
} from './sessionRole'

function createStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  }
}

beforeEach(() => {
  clearStoredSessionRole(null)
})

describe('세션 역할 힌트', () => {
  it('로그인 역할을 저장하고 다시 읽는다', () => {
    const storage = createStorage()

    storeSessionRole('CUSTOMER', storage)

    expect(storage.getItem(SESSION_ROLE_STORAGE_KEY)).toBe('CUSTOMER')
    expect(readStoredSessionRole(storage)).toBe('CUSTOMER')
  })

  it('저장된 역할이 없으면 null을 반환한다', () => {
    expect(readStoredSessionRole(createStorage())).toBeNull()
  })

  it('알 수 없는 값은 제거하고 사용하지 않는다', () => {
    const storage = createStorage()
    storage.setItem(SESSION_ROLE_STORAGE_KEY, 'ADMIN')

    expect(readStoredSessionRole(storage)).toBeNull()
    expect(storage.getItem(SESSION_ROLE_STORAGE_KEY)).toBeNull()
  })

  it('로그아웃·만료 시 저장된 역할을 제거한다', () => {
    const storage = createStorage()
    storeSessionRole('RIDER', storage)

    clearStoredSessionRole(storage)

    expect(readStoredSessionRole(storage)).toBeNull()
  })
})
