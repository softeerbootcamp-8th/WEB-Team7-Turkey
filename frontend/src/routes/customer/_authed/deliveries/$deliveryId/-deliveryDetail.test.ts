import { describe, expect, it } from 'vitest'
import {
  formatDetailAddress,
  formatDetailContact,
  formatDetailDateTime,
  formatDetailDistance,
  formatDetailFare,
  formatDetailItemType,
  getDeliveryCancelErrorMessage,
  getDeliveryDetailErrorState,
  isDeliveryCancelSuccess,
  resolveProofImageSource,
} from './-deliveryDetail'

describe('delivery detail', () => {
  it('주소와 연락처 스냅샷을 표시한다', () => {
    expect(formatDetailAddress({ roadAddress: '서울시 강남구', detailAddress: '101호' }))
      .toBe('서울시 강남구 101호')
    expect(formatDetailContact({ name: '홍길동', phoneNumber: '010-1234-5678' }))
      .toBe('홍길동 · 010-1234-5678')
  })

  it('물품, 시각, 거리, 요금을 고객용 표기로 변환한다', () => {
    expect(formatDetailItemType('DOCUMENT')).toBe('문서')
    expect(formatDetailItemType('UNKNOWN')).toBe('물품 정보 미제공')
    expect(formatDetailDateTime('invalid')).toBe('시각 미제공')
    expect(formatDetailDistance(3200)).toBe('3.2km')
    expect(formatDetailFare(63100)).toBe('63,100원')
  })

  it('404 상세 조회를 목록 안내 상태로 구분한다', () => {
    const error = { isAxiosError: true, response: { status: 404 } }
    expect(getDeliveryDetailErrorState(error)).toEqual({
      notFound: true,
      message: '배송요청을 찾을 수 없거나 접근할 수 없습니다.',
    })
  })

  it('취소 409를 배차 완료 안내로 변환한다', () => {
    const error = { isAxiosError: true, response: { status: 409 } }
    expect(getDeliveryCancelErrorMessage(error)).toBe('이미 배차되어 취소할 수 없습니다.')
  })

  it('취소 응답의 성공 상태를 검증한다', () => {
    expect(isDeliveryCancelSuccess({ success: true, data: { status: 'CANCELED' } })).toBe(true)
    expect(isDeliveryCancelSuccess({ success: true })).toBe(false)
  })

  it('웹 사진 주소는 그대로 사용한다', () => {
    expect(resolveProofImageSource('https://cdn.example.com/proof/photo.png', 'production-web'))
      .toBe('https://cdn.example.com/proof/photo.png')
    expect(resolveProofImageSource('/api/proofs/1', 'production-web')).toBe('/api/proofs/1')
  })

  it('로컬 사진 경로는 개발 서버용 주소로 변환한다', () => {
    expect(resolveProofImageSource('/Users/admin/data/배송 사진.png', 'development-web'))
      .toBe('/@fs/Users/admin/data/%EB%B0%B0%EC%86%A1%20%EC%82%AC%EC%A7%84.png')
    expect(resolveProofImageSource('/Users/admin/data/photo.png', 'production-web')).toBeNull()
  })
})
