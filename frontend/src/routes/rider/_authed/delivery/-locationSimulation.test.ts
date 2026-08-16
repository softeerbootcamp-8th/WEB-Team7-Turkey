import { describe, expect, it } from 'vitest'
import {
  advanceTowards,
  distanceMeters,
  SIMULATION_SPEED_SCALE,
  simulatedTravelMeters,
  WALKING_SPEED_METERS_PER_SECOND,
} from './-locationSimulation'

describe('라이더 위치 시뮬레이션', () => {
  it('1배속부터 기본 보행 속도의 2배로 이동한다', () => {
    expect(simulatedTravelMeters(1, 5)).toBe(
      WALKING_SPEED_METERS_PER_SECOND * SIMULATION_SPEED_SCALE * 5,
    )
  })

  it('배속을 1~16 범위로 제한한다', () => {
    expect(simulatedTravelMeters(0, 1)).toBe(WALKING_SPEED_METERS_PER_SECOND * SIMULATION_SPEED_SCALE)
    expect(simulatedTravelMeters(16, 1)).toBe(WALKING_SPEED_METERS_PER_SECOND * SIMULATION_SPEED_SCALE * 16)
    expect(simulatedTravelMeters(20, 1)).toBe(WALKING_SPEED_METERS_PER_SECOND * SIMULATION_SPEED_SCALE * 16)
  })

  it('목표를 향해 이동하면서 남은 거리를 줄인다', () => {
    const start = { latitude: 37.4979, longitude: 127.0276 }
    const target = { latitude: 37.5079, longitude: 127.0376 }
    const next = advanceTowards(start, target, 100)

    expect(distanceMeters(next, target)).toBeLessThan(distanceMeters(start, target))
    expect(distanceMeters(start, next)).toBeCloseTo(100, 0)
  })

  it('남은 거리보다 많이 이동하면 목표 좌표에 멈춘다', () => {
    const start = { latitude: 37.4979, longitude: 127.0276 }
    const target = { latitude: 37.498, longitude: 127.0277 }

    expect(advanceTowards(start, target, 100)).toBe(target)
  })
})
