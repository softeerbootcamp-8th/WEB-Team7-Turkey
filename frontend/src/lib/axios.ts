import Axios, { isAxiosError, type AxiosError, type AxiosRequestConfig } from 'axios'

/**
 * 공용 axios 인스턴스.
 * 인증은 쿠키 기반 서버 세션이므로 withCredentials 로 세션 쿠키를 함께 전송한다.
 *
 * baseURL 기본값은 빈 문자열이다. OpenAPI 스펙의 경로가 이미 `/api/...` 로 시작하고
 * Orval 생성 코드가 그 경로를 그대로 넘기므로, baseURL 에 `/api` 를 두면 `/api/api/...` 가 된다(#195).
 * 개발은 vite proxy(`/api` → localhost:8080), 배포는 CloudFront 의 `/api/*` behavior 가 받으므로
 * 같은 Origin 상대 경로로 충분하다. Origin 을 분리해야 하면 VITE_API_BASE_URL 에 origin 만 넣는다.
 */
export const axiosInstance = Axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  withCredentials: true,
})

/**
 * 401 을 화면이 직접 처리해야 하는 경로.
 *
 * - 세션 확인: 401 은 "그 역할로 로그인되어 있지 않다"는 가드의 정상 판정 신호다.
 *   여기서 리다이렉트하면 비인증 가드(`auth/*`)가 401 을 받는 순간 로그인 화면으로 튕긴다.
 * - 로그인: 자격 증명 오류도 401 이다. 폼에 오류를 표시해야 하는데 리다이렉트하면 입력이 날아간다.
 */
const UNAUTHORIZED_PASSTHROUGH_PATHS = [
  '/api/customer/session',
  '/api/rider/session',
  '/api/customer/login',
  '/api/rider/login',
]

type UnauthorizedHandler = () => void

let unauthorizedHandler: UnauthorizedHandler | null = null

/**
 * 401 공통 처리를 등록한다(`main.tsx` 에서 라우터·queryClient 와 함께 배선).
 *
 * 라우터 인스턴스를 이 모듈에서 직접 import 하면 axios → router → 생성 훅 → axios 로 순환하므로,
 * 주입 방식으로 끊는다. 화면 컴포넌트는 401 을 개별 처리하지 않는다(#195).
 */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler
}

axiosInstance.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (isAxiosError(error) && error.response?.status === 401) {
      const url = error.config?.url ?? ''
      const passthrough = UNAUTHORIZED_PASSTHROUGH_PATHS.some((path) => url.startsWith(path))
      if (!passthrough) {
        unauthorizedHandler?.()
      }
    }
    return Promise.reject(error)
  },
)

/**
 * Orval mutator.
 * Orval 이 생성한 코드는 이 함수를 `customInstance(config, options)` 형태로 호출한다.
 * - 응답 body(`data`)만 언랩해서 반환 → 훅의 데이터 타입이 AxiosResponse<T> 가 아닌 T 가 된다.
 * - React Query 가 언마운트/재요청 시 호출할 수 있도록 `.cancel()` 를 붙인다.
 */
export const customInstance = <T>(
  config: AxiosRequestConfig,
  options?: AxiosRequestConfig,
): Promise<T> => {
  const source = Axios.CancelToken.source()
  const promise = axiosInstance({
    ...config,
    ...options,
    cancelToken: source.token,
  }).then(({ data }) => data as T)

  // React Query 의 요청 취소 지원
  ;(promise as Promise<T> & { cancel?: () => void }).cancel = () => {
    source.cancel('Query was cancelled')
  }

  return promise
}

/** Orval override.errorType / bodyType 에서 참조하는 타입 별칭 */
export type ErrorType<Error> = AxiosError<Error>
export type BodyType<BodyData> = BodyData

export default axiosInstance
