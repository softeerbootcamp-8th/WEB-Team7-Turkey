export interface SimulationCoordinate {
  latitude: number
  longitude: number
}

export const WALKING_SPEED_METERS_PER_SECOND = 1.4
export const SIMULATION_SPEED_SCALE = 2
export const MIN_SIMULATION_SPEED = 1
export const MAX_SIMULATION_SPEED = 16
export const LOCATION_SIMULATION_INTERVAL_MS = 1_000

const EARTH_RADIUS_METERS = 6_371_000

function toRadians(degrees: number): number {
  return degrees * Math.PI / 180
}

/** 두 좌표 사이의 직선 거리를 Haversine 공식으로 계산한다. */
export function distanceMeters(from: SimulationCoordinate, to: SimulationCoordinate): number {
  const latitudeDelta = toRadians(to.latitude - from.latitude)
  const longitudeDelta = toRadians(to.longitude - from.longitude)
  const fromLatitude = toRadians(from.latitude)
  const toLatitude = toRadians(to.latitude)
  const haversine = Math.sin(latitudeDelta / 2) ** 2
    + Math.cos(fromLatitude) * Math.cos(toLatitude) * Math.sin(longitudeDelta / 2) ** 2

  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(haversine))
}

/** 주어진 거리만큼 목표 좌표를 향해 이동하되 목표를 지나치지 않는다. */
export function advanceTowards(
  from: SimulationCoordinate,
  to: SimulationCoordinate,
  travelMeters: number,
): SimulationCoordinate {
  const remainingMeters = distanceMeters(from, to)
  if (remainingMeters === 0 || travelMeters >= remainingMeters) {
    return to
  }
  if (travelMeters <= 0) {
    return from
  }

  const ratio = travelMeters / remainingMeters
  return {
    latitude: from.latitude + (to.latitude - from.latitude) * ratio,
    longitude: from.longitude + (to.longitude - from.longitude) * ratio,
  }
}

export function simulatedTravelMeters(speedMultiplier: number, elapsedSeconds: number): number {
  const normalizedMultiplier = Math.min(
    MAX_SIMULATION_SPEED,
    Math.max(MIN_SIMULATION_SPEED, speedMultiplier),
  )
  return WALKING_SPEED_METERS_PER_SECOND
    * SIMULATION_SPEED_SCALE
    * normalizedMultiplier
    * Math.max(0, elapsedSeconds)
}
