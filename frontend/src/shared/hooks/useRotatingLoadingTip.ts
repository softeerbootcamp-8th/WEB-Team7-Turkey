import { useEffect, useState } from 'react'
import { loadingTips } from '@/shared/content/loadingTips'

const ROTATION_INTERVAL_MS = 3_000

export function useRotatingLoadingTip(): string {
  const [tipIndex, setTipIndex] = useState(
    () => Math.floor(Math.random() * loadingTips.length),
  )

  useEffect(() => {
    const intervalId = window.setInterval(() => {
      setTipIndex((current) => (current + 1) % loadingTips.length)
    }, ROTATION_INTERVAL_MS)

    return () => window.clearInterval(intervalId)
  }, [])

  return loadingTips[tipIndex]
}
