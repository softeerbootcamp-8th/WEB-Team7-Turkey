import type { ErrorComponentProps } from '@tanstack/react-router'

/**
 * 세션 확인 자체가 실패했을 때(네트워크·5xx) 보여주는 화면.
 *
 * 만료와 장애를 구분하는 것이 요점이다. 오류를 만료로 처리해 로그인 화면으로 보내면
 * 일시적 서버 장애에 전원 로그아웃된다(#195 예외 흐름).
 */
export function SessionErrorScreen({ error, reset }: ErrorComponentProps) {
  return (
    <main aria-label="세션 확인 오류" className="flex min-h-screen flex-col items-center justify-center gap-4 px-6">
      <div role="alert" className="flex flex-col items-center gap-2 text-center">
        <h1 className="text-lg font-semibold text-on-surface">로그인 상태를 확인할 수 없습니다</h1>
        <p className="text-sm text-on-surface-variant">
          일시적인 문제일 수 있습니다. 잠시 후 다시 시도해 주세요.
        </p>
        {import.meta.env.DEV && (
          <p className="text-xs text-on-surface-variant">{error instanceof Error ? error.message : String(error)}</p>
        )}
      </div>
      <button
        type="button"
        onClick={reset}
        className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary"
      >
        다시 시도
      </button>
    </main>
  )
}
