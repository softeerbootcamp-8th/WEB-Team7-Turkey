import { useState } from 'react'
import {
  MAX_SIMULATION_SPEED,
  MIN_SIMULATION_SPEED,
  type SimulationCoordinate,
} from '../-locationSimulation'
import { useLocationSimulator } from '../-useLocationSimulator'

export function LocationSimulationControl({
  start,
  target,
}: {
  start: SimulationCoordinate | null
  target: SimulationCoordinate | null
}) {
  const [expanded, setExpanded] = useState(false)
  const simulator = useLocationSimulator(start, target)
  const unavailable = !start || !target

  if (!expanded) {
    return (
      <div className="pointer-events-none fixed inset-x-0 top-20 z-50 mx-auto flex w-full max-w-md justify-end px-3">
        <button
          type="button"
          aria-label="GPS 이동 시뮬레이션 설정 열기"
          onClick={() => setExpanded(true)}
          className={`pointer-events-auto flex h-11 w-11 items-center justify-center rounded-full border border-surface-container shadow-md transition-colors ${simulator.enabled ? 'bg-primary-container text-on-primary-container' : 'bg-surface-container-lowest text-on-surface'}`}
        >
          <span className="material-symbols-outlined text-xl" aria-hidden="true">my_location</span>
        </button>
      </div>
    )
  }

  return (
    <div className="pointer-events-none fixed inset-x-0 top-20 z-50 mx-auto flex w-full max-w-md justify-end px-3">
      <div className="pointer-events-auto flex max-w-full flex-col items-end gap-1">
        <div
          className="flex h-11 max-w-full items-center gap-1.5 rounded-full border border-surface-container bg-surface-container-lowest px-2 shadow-md"
          title="목표 지점 GPS 이동 시뮬레이션"
        >
          <button
            type="button"
            aria-label="GPS 이동 시뮬레이션 설정 닫기"
            onClick={() => setExpanded(false)}
            className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-secondary transition-colors hover:bg-surface-container"
          >
            <span className="material-symbols-outlined text-lg" aria-hidden="true">close</span>
          </button>
          <span className="text-[10px] font-bold text-secondary">GPS</span>
          <button
            type="button"
            role="switch"
            aria-checked={simulator.enabled}
            aria-label="GPS 이동 시뮬레이션"
            disabled={simulator.starting || unavailable}
            onClick={simulator.toggle}
            className={`relative h-5 w-9 shrink-0 rounded-full transition-colors disabled:cursor-not-allowed disabled:opacity-40 ${simulator.enabled ? 'bg-primary-container' : 'bg-outline-variant'}`}
          >
            <span
              aria-hidden="true"
              className={`absolute left-0.5 top-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform ${simulator.enabled ? 'translate-x-4' : 'translate-x-0'}`}
            />
          </button>
          <input
            type="range"
            min={MIN_SIMULATION_SPEED}
            max={MAX_SIMULATION_SPEED}
            step={1}
            value={simulator.speedMultiplier}
            onChange={(event) => simulator.setSpeedMultiplier(Number(event.target.value))}
            aria-label="GPS 이동 시뮬레이션 배속"
            className="h-1 w-20 cursor-pointer accent-primary-container"
          />
          <output className="w-7 text-right font-label-sm text-label-sm font-bold text-on-surface">
            {simulator.speedMultiplier}×
          </output>
        </div>
        {simulator.starting && <span className="text-[10px] leading-none text-secondary">시뮬레이션 준비 중…</span>}
        {simulator.error && <span role="alert" className="max-w-40 text-right text-[10px] leading-tight text-error">{simulator.error}</span>}
      </div>
    </div>
  )
}
