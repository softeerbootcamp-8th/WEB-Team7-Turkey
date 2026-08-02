import { queryOptions, type QueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import {
  getCustomerSession,
  getGetCustomerSessionQueryKey,
} from '@/api/generated/customer-session/customer-session'
import { getGetRiderSessionQueryKey, getRiderSession } from '@/api/generated/rider-session/rider-session'
import type {
  CustomerSessionResponse,
  RiderSessionResponse,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import type { SessionInfo } from './guard'
import {
  clearStoredSessionRole,
  readStoredSessionRole,
  storeSessionRole,
  type SessionRole,
} from './sessionRole'

/**
 * 세션 확인 쿼리와 저장된 역할 힌트에 따른 단일 조회.
 *
 * 역할별 세션 API 를 병렬 호출하면 반대 역할의 401 응답이 공용 SESSION_ID 쿠키를 만료시킨다(#288).
 * 로그인 성공 시 저장한 역할은 조회 대상을 고르는 힌트일 뿐이며, 인증·인가는 서버 세션이 담당한다.
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
 * 저장된 역할과 일치하는 신선한 세션 캐시가 있으면 네트워크를 다시 조회하지 않는다.
 * 다른 탭에서 역할 힌트가 바뀐 경우에는 이전 역할 캐시를 재사용하지 않는다.
 */
function readFreshSession(queryClient: QueryClient, role: SessionRole): SessionInfo | undefined {
  const now = Date.now()

  if (role === 'CUSTOMER') {
    const customerState = queryClient.getQueryState<Awaited<ReturnType<typeof getCustomerSession>>>(
      getGetCustomerSessionQueryKey(),
    )
    if (customerState?.data?.data && now - customerState.dataUpdatedAt < SESSION_STALE_TIME) {
      return { role: 'CUSTOMER', customer: customerState.data.data }
    }
    return undefined
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
  const role = readStoredSessionRole()
  if (!role) {
    return { role: null }
  }

  const cached = readFreshSession(queryClient, role)
  if (cached) {
    return cached
  }

  try {
    if (role === 'CUSTOMER') {
      const customer = await queryClient.ensureQueryData(customerSessionQueryOptions())
      if (!customer.data) {
        throw new MalformedSessionResponseError('CUSTOMER')
      }
      return { role: 'CUSTOMER', customer: customer.data }
    }

    const rider = await queryClient.ensureQueryData(riderSessionQueryOptions())
    if (!rider.data) {
      throw new MalformedSessionResponseError('RIDER')
    }
    return { role: 'RIDER', rider: rider.data }
  } catch (error) {
    if (isUnauthorized(error)) {
      clearSessionQueries(queryClient)
      return { role: null }
    }

    throw error
  }
}

/** 로그아웃·만료 시 역할 힌트와 캐시된 세션을 함께 버린다. */
export function clearSessionQueries(queryClient: QueryClient): void {
  clearStoredSessionRole()
  queryClient.removeQueries({ queryKey: getGetCustomerSessionQueryKey() })
  queryClient.removeQueries({ queryKey: getGetRiderSessionQueryKey() })
}

/**
 * 고객 로그인 응답으로 고객 세션 캐시를 확정한다.
 *
 * 로그인 직후 역할은 이미 CUSTOMER 로 확인됐다. 이때 캐시를 비워 둔 채 가드가 역할을 다시
 * 탐색하면 고객·라이더 세션 API 를 함께 호출하고, 라이더 역할 불일치 401 의 만료 쿠키가 방금
 * 발급된 공용 SESSION_ID 를 삭제할 수 있다. 반대 역할을 재조회하지 않도록 로그인 응답을
 * 고객 세션 조회와 같은 형태로 저장한다.
 */
export function cacheAuthenticatedCustomer(
  queryClient: QueryClient,
  customer: CustomerSessionResponse,
): void {
  clearSessionQueries(queryClient)
  storeSessionRole('CUSTOMER')
  queryClient.setQueryData(getGetCustomerSessionQueryKey(), {
    success: true,
    data: customer,
  })
}

/** 라이더 로그인 응답으로 역할 힌트와 라이더 세션 캐시를 확정한다. */
export function cacheAuthenticatedRider(queryClient: QueryClient, rider: RiderSessionResponse): void {
  clearSessionQueries(queryClient)
  storeSessionRole('RIDER')
  queryClient.setQueryData(getGetRiderSessionQueryKey(), {
    success: true,
    data: rider,
  })
}
