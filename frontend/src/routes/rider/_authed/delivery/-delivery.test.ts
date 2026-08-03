import { describe, expect, it } from 'vitest'
import {
  formatAddress,
  formatDeliveryAmount,
  formatDeliveryDistance,
  getDeliveryStageContent,
  getKakaoNavigationUrl,
} from './-delivery'

describe('진행 배송 화면 복구', () => {
  it('서버 배송 상태를 화면 단계와 다음 버튼으로 변환한다', () => {
    expect(getDeliveryStageContent('ASSIGNED')?.actionLabel).toBe('픽업 출발하기')
    expect(getDeliveryStageContent('MOVING_TO_PICKUP')?.actionLabel).toBe('픽업 완료하기')
    expect(getDeliveryStageContent('PICKED_UP')?.actionLabel).toBe('배송 출발하기')
    expect(getDeliveryStageContent('DELIVERING')?.navigationTarget).toBe('destination')
    expect(getDeliveryStageContent('COMPLETED')).toBeNull()
  })

  it('주소·거리·정산액을 표시값으로 변환한다', () => {
    expect(formatAddress({ roadAddress: '서울시 강남구', detailAddress: '101호' })).toBe('서울시 강남구 101호')
    expect(formatDeliveryDistance(850)).toBe('850m')
    expect(formatDeliveryDistance(3200)).toBe('3.2km')
    expect(formatDeliveryAmount(6400)).toBe('6,400원')
  })

  it('좌표가 있을 때 카카오맵 길안내 주소를 만든다', () => {
    expect(getKakaoNavigationUrl({ roadAddress: '서울역', latitude: 37.55, longitude: 126.97 }))
      .toBe('https://map.kakao.com/link/to/%EC%84%9C%EC%9A%B8%EC%97%AD,37.55,126.97')
    expect(getKakaoNavigationUrl({ roadAddress: '서울역' })).toBeNull()
  })
})
