import type {
  AddressRequest,
  DeliveryCreateRequest,
  FareQuoteRequest,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'

export type AddressField = {
  roadAddress: string
  detailAddress: string
  postalCode: string
  latitude: string
  longitude: string
}

export type ContactField = {
  name: string
  phoneNumber: string
}

export type DeliveryFormValues = {
  itemType: DeliveryCreateRequest['itemType']
  pickup: AddressField
  destination: AddressField
  sender: ContactField
  recipient: ContactField
  riderNote: string
}

export type DeliveryFormErrors = Partial<Record<
  | 'pickup.roadAddress'
  | 'pickup.postalCode'
  | 'pickup.latitude'
  | 'pickup.longitude'
  | 'destination.roadAddress'
  | 'destination.postalCode'
  | 'destination.latitude'
  | 'destination.longitude'
  | 'sender.name'
  | 'sender.phoneNumber'
  | 'recipient.name'
  | 'recipient.phoneNumber',
  string
>>

export const INITIAL_DELIVERY_FORM_VALUES: DeliveryFormValues = {
  itemType: 'DOCUMENT',
  pickup: {
    roadAddress: '',
    detailAddress: '',
    postalCode: '',
    latitude: '',
    longitude: '',
  },
  destination: {
    roadAddress: '',
    detailAddress: '',
    postalCode: '',
    latitude: '',
    longitude: '',
  },
  sender: { name: '', phoneNumber: '' },
  recipient: { name: '', phoneNumber: '' },
  riderNote: '',
}

function coordinateError(value: string, minimum: number, maximum: number): string | undefined {
  if (!value.trim()) {
    return '필수 입력 항목입니다.'
  }

  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed < minimum || parsed > maximum) {
    return `${minimum}에서 ${maximum} 사이의 숫자를 입력해 주세요.`
  }

  return undefined
}

export function validateDeliveryForm(values: DeliveryFormValues): DeliveryFormErrors {
  const errors: DeliveryFormErrors = {}

  for (const location of ['pickup', 'destination'] as const) {
    if (!values[location].roadAddress.trim()) {
      errors[`${location}.roadAddress`] = '도로명 주소를 입력해 주세요.'
    }
    if (!values[location].postalCode.trim()) {
      errors[`${location}.postalCode`] = '우편번호를 입력해 주세요.'
    }

    const latitudeError = coordinateError(values[location].latitude, -90, 90)
    if (latitudeError) {
      errors[`${location}.latitude`] = latitudeError
    }
    const longitudeError = coordinateError(values[location].longitude, -180, 180)
    if (longitudeError) {
      errors[`${location}.longitude`] = longitudeError
    }
  }

  for (const contact of ['sender', 'recipient'] as const) {
    if (!values[contact].name.trim()) {
      errors[`${contact}.name`] = '이름을 입력해 주세요.'
    }
    if (!values[contact].phoneNumber.trim()) {
      errors[`${contact}.phoneNumber`] = '연락처를 입력해 주세요.'
    }
  }

  return errors
}

function toAddressRequest(address: AddressField): AddressRequest {
  return {
    roadAddress: address.roadAddress.trim(),
    detailAddress: address.detailAddress.trim() || undefined,
    postalCode: address.postalCode.trim(),
    latitude: Number(address.latitude),
    longitude: Number(address.longitude),
  }
}

export function toFareQuoteRequest(values: DeliveryFormValues): FareQuoteRequest {
  return {
    itemType: values.itemType,
    pickupAddress: toAddressRequest(values.pickup),
    destinationAddress: toAddressRequest(values.destination),
  }
}

export function toDeliveryCreateRequest(
  values: DeliveryFormValues,
  estimatedFare: number,
  requestKey: string,
): DeliveryCreateRequest {
  return {
    requestKey,
    estimatedFare,
    itemType: values.itemType,
    pickup: toAddressRequest(values.pickup),
    destination: toAddressRequest(values.destination),
    sender: {
      name: values.sender.name.trim(),
      phoneNumber: values.sender.phoneNumber.trim(),
    },
    recipient: {
      name: values.recipient.name.trim(),
      phoneNumber: values.recipient.phoneNumber.trim(),
    },
  }
}

export function hasQuoteInputErrors(errors: DeliveryFormErrors): boolean {
  return Object.keys(errors).some((field) => field.startsWith('pickup.') || field.startsWith('destination.'))
}
