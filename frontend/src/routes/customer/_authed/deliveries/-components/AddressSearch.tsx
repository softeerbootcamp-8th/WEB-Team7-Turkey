import { useState } from 'react'
import { loadDaumPostcode } from '@/lib/daumPostcode'
import { loadKakaoMaps } from '@/lib/kakaoMaps'
import type { AddressField } from '../-deliveryForm'

type AddressSearchProps = {
  label: string
  value: AddressField
  onSelect: (address: AddressField) => void
}

function geocodeAddress(address: string): Promise<{ latitude: string; longitude: string }> {
  return loadKakaoMaps().then((kakaoSdk) => new Promise((resolve, reject) => {
    const geocoder = new kakaoSdk.maps.services.Geocoder()
    geocoder.addressSearch(address, (results, status) => {
      if (status !== kakaoSdk.maps.services.Status.OK || !results[0]) {
        reject(new Error('선택한 주소의 좌표를 찾지 못했습니다.'))
        return
      }
      resolve({ latitude: results[0].y, longitude: results[0].x })
    })
  }))
}

export function AddressSearch({ label, value, onSelect }: AddressSearchProps) {
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string>()

  async function openAddressSearch() {
    setError(undefined)
    setIsLoading(true)

    try {
      const postcodeSdk = await loadDaumPostcode()
      let selected = false
      new postcodeSdk.Postcode({
        oncomplete: (data) => {
          selected = true
          const roadAddress = data.roadAddress || data.address
          void geocodeAddress(roadAddress)
            .then((coordinates) => {
              onSelect({
                ...value,
                roadAddress,
                postalCode: data.zonecode,
                ...coordinates,
              })
            })
            .catch((geocodeError: unknown) => {
              setError(geocodeError instanceof Error ? geocodeError.message : '좌표 변환에 실패했습니다.')
            })
            .finally(() => setIsLoading(false))
        },
        onclose: () => {
          if (!selected) {
            setIsLoading(false)
          }
        },
      }).open()
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '주소 검색을 시작하지 못했습니다.')
      setIsLoading(false)
    }
  }

  return (
    <div>
      <button
        type="button"
        onClick={openAddressSearch}
        disabled={isLoading}
        className="flex w-full items-center justify-between rounded-lg border border-gray-200 bg-gray-50 p-3 text-left text-sm disabled:opacity-60"
      >
        <span className={value.roadAddress ? 'text-gray-900' : 'text-gray-400'}>
          {value.roadAddress || `${label} 검색 *`}
        </span>
        <span className="ml-3 shrink-0 font-bold text-blue-600">
          {isLoading ? '좌표 확인 중…' : '주소 검색'}
        </span>
      </button>
      {value.postalCode && (
        <p className="mt-1 text-xs text-gray-500">
          우편번호 {value.postalCode} · 좌표 {value.latitude}, {value.longitude}
        </p>
      )}
      {error && <p role="alert" className="mt-1 text-xs text-red-600">{error}</p>}
    </div>
  )
}
