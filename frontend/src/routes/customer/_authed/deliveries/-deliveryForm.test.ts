import { describe, expect, it } from 'vitest'
import {
  INITIAL_DELIVERY_FORM_VALUES,
  hasQuoteInputErrors,
  toDeliveryCreateRequest,
  toFareQuoteRequest,
  validateDeliveryForm,
  type DeliveryFormValues,
} from './-deliveryForm'

function validValues(): DeliveryFormValues {
  return {
    ...INITIAL_DELIVERY_FORM_VALUES,
    pickup: {
      roadAddress: '서울 강남구 테헤란로 1',
      detailAddress: '3층',
      postalCode: '06234',
      latitude: '37.5001',
      longitude: '127.0350',
    },
    destination: {
      roadAddress: '서울 서초구 서초대로 2',
      detailAddress: '',
      postalCode: '06611',
      latitude: '37.4920',
      longitude: '127.0290',
    },
    sender: { name: '보내는 사람', phoneNumber: '010-1111-2222' },
    recipient: { name: '받는 사람', phoneNumber: '010-3333-4444' },
    riderNote: '도착 전에 연락해 주세요.',
  }
}

describe('배송요청 생성 폼', () => {
  it('필수 입력과 좌표 범위를 검증한다', () => {
    const values = validValues()
    values.pickup.roadAddress = ' '
    values.destination.latitude = '91'

    const errors = validateDeliveryForm(values)

    expect(errors['pickup.roadAddress']).toBe('도로명 주소를 입력해 주세요.')
    expect(errors['destination.latitude']).toContain('-90에서 90 사이')
    expect(hasQuoteInputErrors(errors)).toBe(true)
  })

  it('유효한 주소와 물품 정보로 견적 요청을 만든다', () => {
    const request = toFareQuoteRequest(validValues())

    expect(request).toEqual({
      itemType: 'DOCUMENT',
      pickupAddress: {
        roadAddress: '서울 강남구 테헤란로 1',
        detailAddress: '3층',
        postalCode: '06234',
        latitude: 37.5001,
        longitude: 127.035,
      },
      destinationAddress: {
        roadAddress: '서울 서초구 서초대로 2',
        detailAddress: undefined,
        postalCode: '06611',
        latitude: 37.492,
        longitude: 127.029,
      },
    })
  })

  it('견적 금액과 멱등키를 포함한 생성 요청을 만든다', () => {
    const request = toDeliveryCreateRequest(
      validValues(),
      8_700,
      '6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34',
    )

    expect(request.estimatedFare).toBe(8_700)
    expect(request.requestKey).toBe('6c1f1a0e-6f7a-4b2b-9a3f-6b0d7f2a1c34')
    expect(request.sender).toEqual({ name: '보내는 사람', phoneNumber: '010-1111-2222' })
    expect(request.recipient).toEqual({ name: '받는 사람', phoneNumber: '010-3333-4444' })
  })
})
