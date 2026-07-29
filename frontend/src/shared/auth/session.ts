import { queryOptions, type QueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import {
  getCustomerSession,
  getGetCustomerSessionQueryKey,
} from '@/api/generated/customer-session/customer-session'
import { getGetRiderSessionQueryKey, getRiderSession } from '@/api/generated/rider-session/rider-session'
import type { SessionInfo } from './guard'

/**
 * 세션 확인 쿼리와, 역할을 모르는 상태에서 로그인 여부를 판정하는 합성 조회.
 *
 * 백엔드 세션 API 는 `customer-session` · `rider-session` 으로 역할별로 갈려 있고 역할 무관
 * 조회 API 가 없다. 그래서 두 API 를 모두 시도해 하나라도 200 이면 그 역할로 로그인된 것으로 본다
 * (#195 사람 확인). 역할 불일치도 서버가 401 로 주므로, 고객 세션 API 의 401 만으로는
 * "비로그인"과 "라이더로 로그인"을 구분할 수 없다 — 그 구분이 이 합성의 존재 이유다.
 */

/**
 * 세션 캐시 유효 시간.
 *
 * 세션 TTL 은 로그인 시점부터 2시간 고정이고 서버가 슬라이딩 갱신을 하지 않는다(#27).
 * 즉 화면 전환마다 세션을 확인해도 TTL 은 늘지 않고 요청만 늘어난다. 반대로 무한정 캐싱하면
 * 다른 탭에서 로그아웃했거나 TTL 이 만료된 뒤에도 가드가 통과시킨다. 5분은 그 사이의 타협이며,
 * 실제 만료는 어차피 API 401 → 인터셉터가 잡는다.
 */
export const SESSION_STALE_TIME = 1000 * 60 * 5

export const customerSessionQueryOptions = () =>
  queryOptions({
    queryKey: getGetCustomerSessionQueryKey(),
    queryFn: ({ signal }) => getCustomerSession(undefined, signal),
    staleTime: SESSION_STALE_TIME,
    // 401 은 "이 역할이 아님"이라는 정상 신호다. 재시도해도 결과가 같고 가드만 느려진다.
    retry: false,
  })

export const riderSessionQueryOptions = () =>
  queryOptions({
    queryKey: getGetRiderSessionQueryKey(),
    queryFn: ({ signal }) => getRiderSession(undefined, signal),
    staleTime: SESSION_STALE_TIME,
    retry: false,
  })

export function isUnauthorized(error: unknown): boolean {
  return isAxiosError(error) && error.response?.status === 401
}

/** 세션 응답이 200 인데 body 에 data 가 없는 경우. 만료로 오해하면 안 되므로 오류로 올린다. */
class MalformedSessionResponseError extends Error {
  constructor(role: 'CUSTOMER' | 'RIDER') {
    super(`${role} 세션 응답에 data 가 없습니다.`)
    this.name = 'MalformedSessionResponseError'
  }
}

/**
 * 캐시에 아직 신선한 세션이 있으면 그것만으로 판정한다.
 *
 * 이게 없으면 라우트를 옮길 때마다 "내 역할이 아닌 쪽" 세션 API 로 실패가 예정된 요청을 매번 보낸다
 * (401 은 캐시되지 않으므로 ensureQueryData 가 계속 재요청한다). 이슈가 요구하는
 * "화면마다 다시 요청하지 않도록 캐시를 공유한다"를 만족시키는 지점이다.
 */
function readFreshSession(queryClient: QueryClient): SessionInfo | undefined {
  const now = Date.now()

  const customerState = queryClient.getQueryState<Awaited<ReturnType<typeof getCustomerSession>>>(
    getGetCustomerSessionQueryKey(),
  )
  if (customerState?.data?.data && now - customerState.dataUpdatedAt < SESSION_STALE_TIME) {
    return { role: 'CUSTOMER', customer: customerState.data.data }
  }

  const riderState = queryClient.getQueryState<Awaited<ReturnType<typeof getRiderSession>>>(
    getGetRiderSessionQueryKey(),
  )
  if (riderState?.data?.data && now - riderState.dataUpdatedAt < SESSION_STALE_TIME) {
    return { role: 'RIDER', rider: riderState.data.data }
  }

  return undefined
}

/**
 * 로그인 여부와 역할을 확인한다.
 *
 * 401 이 아닌 실패(네트워크·5xx)는 그대로 throw 한다 — 만료로 처리하면 일시적 장애에 전원
 * 로그아웃되기 때문이다. 라우트는 이 throw 를 errorComponent 로 받아 오류 상태를 보여준다.
 */
export async function ensureSessionInfo(queryClient: QueryClient): Promise<SessionInfo> {
  const cached = readFreshSession(queryClient)
  if (cached) {
    return cached
  }

  const [customer, rider] = await Promise.allSettled([
    queryClient.ensureQueryData(customerSessionQueryOptions()),
    queryClient.ensureQueryData(riderSessionQueryOptions()),
  ])

  if (customer.status === 'fulfilled') {
    if (!customer.value.data) {
      throw new MalformedSessionResponseError('CUSTOMER')
    }
    return { role: 'CUSTOMER', customer: customer.value.data }
  }
  if (rider.status === 'fulfilled') {
    if (!rider.value.data) {
      throw new MalformedSessionResponseError('RIDER')
    }
    return { role: 'RIDER', rider: rider.value.data }
  }

  const unexpected = [customer.reason, rider.reason].find((reason) => !isUnauthorized(reason))
  if (unexpected) {
    throw unexpected
  }

  // 양쪽 모두 401 — 어느 역할로도 로그인되어 있지 않다.
  return { role: null }
}

/** 로그아웃·만료 시 캐시된 세션을 버린다. invalidate 가 아니라 remove — 만료된 세션을 남겨둘 이유가 없다. */
export function clearSessionQueries(queryClient: QueryClient): void {
  queryClient.removeQueries({ queryKey: getGetCustomerSessionQueryKey() })
  queryClient.removeQueries({ queryKey: getGetRiderSessionQueryKey() })
}
