import { useEffect, useState } from 'react'
import { formatWaitingCountdown } from '../-waitingCountdown'

interface WaitingCountdownProps {
  /** 주문 생성 시각(서버 UTC, 오프셋 없음). 이 값 + 5분이 자동 취소 시점이다. */
  requestedAt: string | undefined
  /** WAITING 상태일 때만 켠다. 꺼지면 tick 도 멈춘다. */
  enabled: boolean
  /** 화면별 문자 스타일. 색·크기는 호출부(디자인 토큰 vs 시안 회색)가 정한다. */
  className?: string
}

/** 화면 눈금 갱신 주기. 초까지 세므로 1초 tick 이 필요하다. */
const TICK_INTERVAL_MS = 1_000

/**
 * 배차 대기 자동 취소까지 남은 시간 카운트다운(#42/#444).
 *
 * <p><b>별도 컴포넌트로 가둔 이유는 tick 때문이다</b>(DeliveryEta 와 같은 이유). 1초마다
 * 갱신되는 상태를 추적 페이지에 두면 지도까지 매초 리렌더된다. 이 컴포넌트만 다시 그린다.
 *
 * <p>만료·값 없음이면 아무것도 렌더하지 않는다 — 실제 자동 취소는 `useWaitingExpiryTimer`
 * 가 걸고, 상태가 CANCELED 로 바뀌면 호출부가 `enabled=false` 로 이 영역을 닫는다.
 */
export function WaitingCountdown({ requestedAt, enabled, className }: WaitingCountdownProps) {
  const [nowMs, setNowMs] = useState(() => Date.now())

  useEffect(() => {
    if (!enabled) {
      return
    }
    const timer = setInterval(() => setNowMs(Date.now()), TICK_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [enabled])

  const countdown = enabled ? formatWaitingCountdown(requestedAt, nowMs) : null
  if (!countdown) {
    return null
  }

  return (
    <p className={className}>
      <strong className="tabular-nums">{countdown}</strong> 안에 라이더가 배정되지 않으면 자동 취소돼요
    </p>
  )
}
