/**
 * 로그인 후 원래 목적지로 돌려보내기 위한 `?redirect=` 검색 파라미터.
 *
 * 가드가 리다이렉트할 수 있는 라우트(`/customer/login`, `/rider/login`, `/`)에서 validateSearch 로 쓴다.
 */
export interface RedirectSearch {
  redirect?: string
}

/**
 * 같은 출처의 절대 경로만 허용한다.
 *
 * 주소창으로 들어오는 값이라 그대로 믿고 이동하면 오픈 리다이렉트가 된다
 * (`?redirect=https://evil.example`, `?redirect=//evil.example`). 경로 형태만 통과시킨다.
 */
export function validateRedirectSearch(search: Record<string, unknown>): RedirectSearch {
  const redirect = search.redirect
  if (typeof redirect !== 'string' || !redirect.startsWith('/') || redirect.startsWith('//')) {
    return {}
  }
  return { redirect }
}
