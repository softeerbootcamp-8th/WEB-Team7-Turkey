import Axios, { type AxiosError, type AxiosRequestConfig } from 'axios'

/**
 * 공용 axios 인스턴스.
 * 인증은 쿠키 기반 서버 세션이므로 withCredentials 로 세션 쿠키를 함께 전송한다.
 * (공통 인터셉터 — 예: 401 → 로그인 리다이렉트 — 는 이 인스턴스에 등록한다)
 */
export const axiosInstance = Axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  withCredentials: true,
})

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
