import { Capacitor } from '@capacitor/core'
import { useCallback, useEffect, useRef, useState } from 'react'
import { updateRiderLocation } from '@/api/generated/rider-location/rider-location'
import { startRiderLocationSender, stopRiderLocationSender } from '@/shared/hooks/useLocationSender'
import {
  advanceTowards,
  LOCATION_SIMULATION_INTERVAL_MS,
  MAX_SIMULATION_SPEED,
  MIN_SIMULATION_SPEED,
  simulatedTravelMeters,
  type SimulationCoordinate,
} from './-locationSimulation'

interface LocationSimulator {
  enabled: boolean
  starting: boolean
  speedMultiplier: number
  error: string | null
  setSpeedMultiplier: (speedMultiplier: number) => void
  toggle: () => void
}

function errorMessage(error: unknown): string {
  return error instanceof Error
    ? error.message
    : '위치 시뮬레이션을 실행하지 못했습니다.'
}

export function useLocationSimulator(
  start: SimulationCoordinate | null,
  target: SimulationCoordinate | null,
): LocationSimulator {
  const [enabled, setEnabled] = useState(false)
  const [starting, setStarting] = useState(false)
  const [speedMultiplier, setSpeedMultiplierState] = useState(MIN_SIMULATION_SPEED)
  const [error, setError] = useState<string | null>(null)
  const mountedRef = useRef(true)
  const enabledRef = useRef(false)
  const nativePausedRef = useRef(false)
  const sendingRef = useRef(false)
  const positionRef = useRef<SimulationCoordinate | null>(null)
  const startRef = useRef(start)
  const targetRef = useRef(target)
  const speedRef = useRef(speedMultiplier)
  const lastTickRef = useRef(0)

  startRef.current = start
  targetRef.current = target
  speedRef.current = speedMultiplier

  const resumeNativeSender = useCallback(async () => {
    if (!nativePausedRef.current) {
      return
    }
    nativePausedRef.current = false
    try {
      await startRiderLocationSender('BUSY')
    } catch (resumeError) {
      console.error('Android 위치 서비스를 다시 시작하지 못했습니다.', resumeError)
      if (mountedRef.current) {
        setError('네이티브 위치 송신을 다시 시작하지 못했습니다.')
      }
    }
  }, [])

  const stopSimulation = useCallback((nextError?: string) => {
    enabledRef.current = false
    if (mountedRef.current) {
      setEnabled(false)
      if (nextError) {
        setError(nextError)
      }
    }
    void resumeNativeSender()
  }, [resumeNativeSender])

  const sendPosition = useCallback(async (position: SimulationCoordinate) => {
    await updateRiderLocation({
      ...position,
      measuredAt: new Date().toISOString(),
      accuracyMeters: 5,
    })
  }, [])

  const startSimulation = useCallback(async () => {
    if (enabledRef.current || starting) {
      return
    }
    const startPosition = startRef.current
    if (!startPosition || !targetRef.current) {
      setError('배송 경로의 출발 또는 목표 좌표가 없습니다.')
      return
    }

    setStarting(true)
    setError(null)
    try {
      if (Capacitor.getPlatform() === 'android') {
        await stopRiderLocationSender()
        nativePausedRef.current = true
      }
      await sendPosition(startPosition)
      if (!mountedRef.current) {
        await resumeNativeSender()
        return
      }

      positionRef.current = startPosition
      lastTickRef.current = Date.now()
      enabledRef.current = true
      setEnabled(true)
    } catch (startError) {
      await resumeNativeSender()
      if (mountedRef.current) {
        setError(errorMessage(startError))
      }
    } finally {
      if (mountedRef.current) {
        setStarting(false)
      }
    }
  }, [resumeNativeSender, sendPosition, starting])

  const toggle = useCallback(() => {
    if (enabledRef.current) {
      stopSimulation()
    } else {
      void startSimulation()
    }
  }, [startSimulation, stopSimulation])

  const setSpeedMultiplier = useCallback((nextSpeed: number) => {
    setSpeedMultiplierState(Math.min(MAX_SIMULATION_SPEED, Math.max(MIN_SIMULATION_SPEED, nextSpeed)))
  }, [])

  useEffect(() => {
    if (!enabled) {
      return
    }

    const intervalId = window.setInterval(() => {
      if (sendingRef.current || !enabledRef.current) {
        return
      }
      const current = positionRef.current
      const currentTarget = targetRef.current
      if (!current || !currentTarget) {
        stopSimulation('현재 배송 단계의 목표 좌표가 없습니다.')
        return
      }

      const now = Date.now()
      const elapsedSeconds = (now - lastTickRef.current) / 1_000
      const next = advanceTowards(
        current,
        currentTarget,
        simulatedTravelMeters(speedRef.current, elapsedSeconds),
      )
      lastTickRef.current = now
      sendingRef.current = true
      void sendPosition(next)
        .then(() => {
          positionRef.current = next
        })
        .catch((sendError: unknown) => {
          stopSimulation(errorMessage(sendError))
        })
        .finally(() => {
          sendingRef.current = false
        })
    }, LOCATION_SIMULATION_INTERVAL_MS)

    return () => window.clearInterval(intervalId)
  }, [enabled, sendPosition, stopSimulation])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      enabledRef.current = false
      void resumeNativeSender()
    }
  }, [resumeNativeSender])

  return {
    enabled,
    starting,
    speedMultiplier,
    error,
    setSpeedMultiplier,
    toggle,
  }
}
