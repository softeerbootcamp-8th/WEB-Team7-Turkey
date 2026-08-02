import { createFileRoute, Link, redirect } from '@tanstack/react-router'
import { Bike, Clock3, MapPin, Menu, Route as RouteIcon, UserRound } from 'lucide-react'
import { useState } from 'react'
import { resolveLandingGuard } from '@/shared/auth/guard'
import { validateRedirectSearch } from '@/shared/auth/redirectSearch'
import { ensureSessionInfo } from '@/shared/auth/session'
import {
  startRiderLocationSender,
  stopRiderLocationSender,
} from '@/shared/hooks/useLocationSender'
import { useRotatingLoadingTip } from '@/shared/hooks/useRotatingLoadingTip'

export const Route = createFileRoute('/')({
  // account/* 는 역할 무관 화면이라 비로그인 시 여기로 보내진다(#195). 목적지를 보존한다.
  validateSearch: validateRedirectSearch,
  loader: async ({ context }) => {
    let session

    try {
      session = await ensureSessionInfo(context.queryClient)
    } catch {
      // 네트워크·서버 오류를 로그아웃으로 오해하지 않는다. 랜딩은 열어 두고 안내만 표시한다.
      return { sessionCheckFailed: true }
    }

    const decision = resolveLandingGuard(session)
    if (decision.action === 'redirect') {
      throw redirect({ to: decision.to })
    }

    return { sessionCheckFailed: false }
  },
  pendingMs: 0,
  pendingMinMs: 300,
  pendingComponent: LandingLoadingScreen,
  component: LandingPage,
})

function LandingPage() {
  const { sessionCheckFailed } = Route.useLoaderData()
  const [locationAction, setLocationAction] = useState<'idle' | 'starting' | 'stopping'>('idle')

  const startLocation = async () => {
    setLocationAction('starting')
    try {
      await startRiderLocationSender()
    } catch (error) {
      console.error('수동 위치 송신 시작에 실패했습니다.', error)
    } finally {
      setLocationAction('idle')
    }
  }

  const stopLocation = async () => {
    setLocationAction('stopping')
    try {
      await stopRiderLocationSender()
    } catch (error) {
      console.error('수동 위치 송신 중지에 실패했습니다.', error)
    } finally {
      setLocationAction('idle')
    }
  }

  return (
    <div className="landing-home">
      <header className="landing-home__header">
        <span className="landing-home__brand" aria-label="Turkey 홈">
          turkey<span aria-hidden="true">.</span>
        </span>
        <div className="flex items-center gap-1.5">
          <button
            type="button"
            disabled={locationAction !== 'idle'}
            onClick={() => void startLocation()}
            className="rounded-md bg-primary-container px-2.5 py-1.5 text-xs font-semibold text-on-primary-container disabled:opacity-50"
          >
            송신 시작
          </button>
          <button
            type="button"
            disabled={locationAction !== 'idle'}
            onClick={() => void stopLocation()}
            className="rounded-md border border-outline-variant bg-surface-container-lowest px-2.5 py-1.5 text-xs font-semibold text-on-surface disabled:opacity-50"
          >
            끄기
          </button>
          <button
            type="button"
            className="landing-home__menu-button"
            aria-label="메뉴 화면 준비 중"
            title="메뉴는 준비 중입니다"
            disabled
          >
            <Menu size={25} strokeWidth={2.3} aria-hidden="true" />
          </button>
        </div>
      </header>

      <main className="landing-home__main" aria-label="Turkey 서비스 소개">
        <section className="landing-home__intro" aria-labelledby="landing-title">
          <p className="landing-home__eyebrow">빠르고 안전한 도심 퀵배송</p>
          <h1 id="landing-title">
            누군가를 보내고 싶은 순간,
            <br />
            <strong>칠면조처럼 바로 달려가요</strong>
          </h1>
          <p className="landing-home__description">
            가까운 라이더를 빠르게 연결하고
            <br />
            배송 위치를 끝까지 확인하세요.
          </p>
        </section>

        <section className="landing-home__visual" aria-label="빠른 퀵배송 연결 과정">
          <span className="landing-home__route-line" aria-hidden="true" />
          <span className="landing-home__pin landing-home__pin--start" aria-hidden="true">
            <MapPin size={18} />
          </span>
          <span className="landing-home__pin landing-home__pin--end" aria-hidden="true">
            <MapPin size={18} />
          </span>
          <div className="landing-home__rider" aria-hidden="true">
            <Bike size={34} strokeWidth={1.9} />
          </div>
          <div className="landing-home__status-card">
            <span className="landing-home__status-icon" aria-hidden="true">
              <Clock3 size={18} />
            </span>
            <span>
              <small>가까운 라이더 연결</small>
              <strong>빠르게 픽업을 시작해요</strong>
            </span>
          </div>
        </section>

        <ul className="landing-home__benefits" aria-label="Turkey 주요 기능">
          <li>
            <Clock3 size={18} aria-hidden="true" />
            <span>빠른 배차</span>
          </li>
          <li>
            <RouteIcon size={18} aria-hidden="true" />
            <span>실시간 위치</span>
          </li>
          <li>
            <Bike size={18} aria-hidden="true" />
            <span>고객·라이더</span>
          </li>
        </ul>

        {sessionCheckFailed && (
          <p className="landing-home__notice" role="alert">
            로그인 상태를 확인하지 못했어요. 아래에서 이용할 역할을 선택해 주세요.
          </p>
        )}
      </main>

      <footer className="landing-home__footer">
        <div className="landing-home__role-actions" aria-label="이용 역할 선택">
          <Link
            to="/customer/login"
            className="landing-home__role-button landing-home__role-button--customer"
          >
            <UserRound size={21} strokeWidth={2.3} aria-hidden="true" />
            <span>고객으로</span>
          </Link>
          <Link
            to="/rider/login"
            className="landing-home__role-button landing-home__role-button--rider"
          >
            <Bike size={22} strokeWidth={2.3} aria-hidden="true" />
            <span>라이더로</span>
          </Link>
        </div>
        <p>원하는 역할을 선택해 보세요</p>
      </footer>
    </div>
  )
}

function LandingLoadingScreen() {
  const loadingTip = useRotatingLoadingTip()

  return (
    <main className="landing-loading" aria-label="로그인 상태 확인 중">
      <span className="landing-loading__brand">
        turkey<span aria-hidden="true">.</span>
      </span>
      <span className="landing-loading__spinner" aria-hidden="true" />
      <p role="status" aria-live="polite" aria-atomic="true">
        {loadingTip}
      </p>
    </main>
  )
}
