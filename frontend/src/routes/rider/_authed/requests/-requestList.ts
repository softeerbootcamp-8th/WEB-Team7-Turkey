import { isAxiosError } from 'axios'
import type {
  ApiResponseRiderDeliveryRequestPageResponse,
  RiderDeliveryRequestSummaryResponse,
  RiderDeliveryRequestSummaryResponseItemType,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { formatSeoul } from '@/shared/lib/datetime'

export const DEFAULT_RADIUS_METERS = 3000

export const RADIUS_OPTIONS = [
  { value: 1000, label: '1km' },
  { value: 3000, label: '3km' },
  { value: 5000, label: '5km' },
  { value: 10000, label: '10km' },
] as const

export type ItemFilter = 'ALL' | RiderDeliveryRequestSummaryResponseItemType

export const ITEM_FILTER_OPTIONS: ReadonlyArray<{ value: ItemFilter; label: string }> = [
  { value: 'ALL', label: '전체' },
  { value: 'DOCUMENT', label: '문서' },
  { value: 'SMALL_PARCEL', label: '소형' },
  { value: 'MEDIUM_PARCEL', label: '중형' },
  { value: 'LARGE_PARCEL', label: '대형' },
  { value: 'FOOD', label: '음식' },
]

const ITEM_TYPE_LABELS: Record<RiderDeliveryRequestSummaryResponseItemType, string> = {
  DOCUMENT: '문서',
  SMALL_PARCEL: '소형',
  MEDIUM_PARCEL: '중형',
  LARGE_PARCEL: '대형',
  FOOD: '음식',
}

export function filterRequestsByItem(
  requests: RiderDeliveryRequestSummaryResponse[],
  itemFilter: ItemFilter,
): RiderDeliveryRequestSummaryResponse[] {
  if (itemFilter === 'ALL') {
    return requests
  }
  return requests.filter((request) => request.itemType === itemFilter)
}

export function formatItemType(itemType: RiderDeliveryRequestSummaryResponseItemType | undefined): string {
  return itemType ? ITEM_TYPE_LABELS[itemType] : '물품 정보 없음'
}

export function formatDistance(meters: number | undefined, fallback = '거리 정보 없음'): string {
  if (meters == null) {
    return fallback
  }
  if (meters < 1000) {
    return `${Math.round(meters).toLocaleString('ko-KR')}m`
  }
  return `${(meters / 1000).toFixed(1)}km`
}

export function formatSettlement(amount: number | undefined): string {
  return amount == null ? '금액 미정' : `${amount.toLocaleString('ko-KR')}P`
}

export function formatRequestedAt(requestedAt: string | undefined): string | null {
  return formatSeoul(requestedAt, { hour: '2-digit', minute: '2-digit' })
}

export interface RequestCursor {
  afterDistanceMeters?: number
  afterFare?: number
  afterRequestedAt?: string
  afterId?: number
}

/**
 * 지금까지 받은 마지막 항목(lastItem) 기준으로 "이 다음부터 보여달라"는 커서를 만든다.
 * 정렬값 + deliveryId 쌍을 담고, 어느 정렬값을 담을지는 hasPosition으로 갈린다:
 *
 *  - 좌표가 있으면 → 픽업거리(afterDistanceMeters). 화면이 sort=DISTANCE로 요청하므로.
 *  - 좌표가 없으면 → 요청시각(afterRequestedAt). 백엔드가 좌표 없는 DISTANCE 요청을 조용히
 *    REQUESTED_AT으로 바꿔 처리하기 때문(#55 계약) — 이때 afterDistanceMeters를 보내면
 *    백엔드가 "정렬 기준과 커서가 안 맞는다"며 요청 자체를 거부한다(requireCursorMatchesSort).
 *
 * lastItem에 필요한 값이 없으면(방어적) undefined를 반환한다 — 호출자는 이 경우 다음 페이지를
 * 요청하지 말아야 한다.
 */
export function buildNextRequestCursor(
  hasPosition: boolean,
  lastItem: RiderDeliveryRequestSummaryResponse | undefined,
): RequestCursor | undefined {
  if (!lastItem || lastItem.deliveryId == null) {
    return undefined
  }
  if (hasPosition) {
    if (lastItem.distanceToPickupMeters == null) {
      return undefined
    }
    return { afterDistanceMeters: lastItem.distanceToPickupMeters, afterId: lastItem.deliveryId }
  }
  if (lastItem.requestedAt == null) {
    return undefined
  }
  return { afterRequestedAt: lastItem.requestedAt, afterId: lastItem.deliveryId }
}

export function getRequestListErrorMessage(error: unknown): string {
  if (!isAxiosError<ApiResponseRiderDeliveryRequestPageResponse>(error)) {
    return '콜 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (!error.response) {
    return '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.'
  }
  return error.response.data?.message || '콜 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

export type RiderPositionUnavailableReason =
  | 'PERMISSION_DENIED'
  | 'POSITION_UNAVAILABLE'
  | 'TIMEOUT'
  | 'UNSUPPORTED'

export interface RiderPosition {
  latitude?: number
  longitude?: number
  unavailableReason?: RiderPositionUnavailableReason
}

export const RIDER_POSITION_OPTIONS: PositionOptions = {
  enableHighAccuracy: false,
  timeout: 5000,
  maximumAge: 60000,
}

/** `GeolocationPositionError.code`를 우리 쪽 사유로 매핑한다. 1=PERMISSION_DENIED, 2=POSITION_UNAVAILABLE, 3=TIMEOUT. */
function toUnavailableReason(code: number): RiderPositionUnavailableReason {
  if (code === 1) {
    return 'PERMISSION_DENIED'
  }
  if (code === 3) {
    return 'TIMEOUT'
  }
  return 'POSITION_UNAVAILABLE'
}

/**
 * 권한 거부·타임아웃·GPS 없음 등 어떤 사유로도 좌표를 못 구하면 빈 값(+사유)으로 resolve한다(#496).
 * 백엔드가 좌표 없는 요청을 이미 "반경 필터 없이 전체 반환"으로 처리하므로, 실패를 던지지 않고
 * 조용히 폴백해야 화면이 예외 상태로 빠지지 않는다.
 */
export function requestRiderPosition(
  geolocation: Pick<Geolocation, 'getCurrentPosition'> | undefined,
  options: PositionOptions = RIDER_POSITION_OPTIONS,
): Promise<RiderPosition> {
  if (!geolocation) {
    return Promise.resolve({ unavailableReason: 'UNSUPPORTED' })
  }
  return new Promise((resolve) => {
    geolocation.getCurrentPosition(
      (position) => resolve({ latitude: position.coords.latitude, longitude: position.coords.longitude }),
      (error) => resolve({ unavailableReason: toUnavailableReason(error.code) }),
      options,
    )
  })
}

/**
 * 기본으로 보여줄, 사유별 짧은 문구(#496).
 *
 * `PERMISSION_DENIED`(code 1)를 "권한 거부"로만 단정하지 않는다 — 실측 결과, 일부 브라우저/OS는
 * 앱·사이트 권한 거부와 시스템 위치 서비스 전체 꺼짐을 구분하지 않고 같은 코드로 뭉쳐서 돌려준다.
 * 그래서 "이 사이트/앱의 권한이 꺼져 있다"처럼 원인을 특정하지 않고, 어디를 봐야 하는지는 아이콘을
 * 눌렀을 때 나오는 `getPositionUnavailableGuidance`에서 두 곳 다 안내한다. `TIMEOUT`만은 코드가
 * 가리키는 뜻이 실제로 신뢰할 수 있어 그대로 쓴다.
 */
export function getPositionUnavailableSummary(reason: RiderPositionUnavailableReason | undefined): string {
  switch (reason) {
    case 'PERMISSION_DENIED':
      // "허용되지 않았다"처럼 권한 프레임에 치우친 표현은 쓰지 않는다 — 시스템 위치 기능
      // 전체가 꺼진 경우도 같은 코드로 들어오는데, "권한"이라고 하면 그 경우를 못 짚는다.
      return '위치 정보를 가져오지 못했어요.'
    case 'TIMEOUT':
      return '위치를 확인하는 데 시간이 오래 걸리고 있어요.'
    case 'POSITION_UNAVAILABLE':
      return '위치 신호를 확인할 수 없어요.'
    default:
      return '위치 정보를 확인할 수 없어요.'
  }
}

/**
 * 라이더 앱은 Capacitor WebView라 주소창이 없다(#496) — "브라우저 설정"을 안내하면 실제
 * 안드로이드 앱 사용자에게는 적용되지 않는 문구가 된다. 안드로이드는 확인할 두 화면이 실제로는
 * 같은 "설정" 앱 안의 다른 경로("앱 → 이 앱 → 권한"과 "위치")라, 그 내비게이션 경로 그대로
 * 짚어준다 — "위치 서비스"처럼 화면에 그대로 없는 추상적인 이름 대신, 기기에 실제로 보이는
 * "위치"라는 항목명을 쓴다.
 */
export function getPositionUnavailableGuidance(platform: string): string {
  if (platform === 'android') {
    return '휴대폰 설정 → 앱 → 이 앱 → 권한에서 위치가 허용되어 있는지, 휴대폰 설정 → 위치에서 위치가 켜져 있는지 둘 다 확인해 주세요.'
  }
  return '브라우저의 이 사이트 위치 권한이 허용되어 있는지, 시스템 설정의 위치 서비스가 켜져 있는지 둘 다 확인해 주세요.'
}
