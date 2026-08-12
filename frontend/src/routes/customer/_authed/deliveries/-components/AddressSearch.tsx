import { useEffect, useRef, useState } from 'react'
import { loadDaumPostcode } from '@/lib/daumPostcode'
import { loadKakaoMaps } from '@/lib/kakaoMaps'
import type { AddressField } from '../-deliveryForm'

type AddressSearchProps = {
  label: string
  value: AddressField
  onSelect: (address: AddressField) => void
}

declare global {
  interface Window {
    __turkeyCloseOverlay?: () => boolean
  }
}

type Coordinates = { latitude: string; longitude: string }

export function getGeocodeCandidates(data: daum.PostcodeData): string[] {
  return [...new Set([
    data.roadAddress,
    data.jibunAddress,
    data.autoRoadAddress,
    data.autoJibunAddress,
    data.address,
  ].map((address) => address.trim()).filter(Boolean))]
}

function searchAddress(
  geocoder: kakao.maps.services.Geocoder,
  kakaoSdk: typeof kakao,
  address: string,
): Promise<{ coordinates?: Coordinates; status: string }> {
  return new Promise((resolve) => {
    geocoder.addressSearch(address, (results, status) => {
      if (status !== kakaoSdk.maps.services.Status.OK || !results[0]) {
        resolve({ status })
        return
      }
      resolve({
        status,
        coordinates: { latitude: results[0].y, longitude: results[0].x },
      })
    })
  })
}

export async function geocodeAddress(addresses: string[]): Promise<Coordinates> {
  const kakaoSdk = await loadKakaoMaps()
  const geocoder = new kakaoSdk.maps.services.Geocoder()

  for (const address of addresses) {
    const result = await searchAddress(geocoder, kakaoSdk, address)
    if (result.coordinates) {
      return result.coordinates
    }
    if (result.status !== kakaoSdk.maps.services.Status.ZERO_RESULT) {
      throw new Error('주소 좌표 변환 서비스에 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.')
    }
  }

  throw new Error('선택한 주소의 좌표를 찾지 못했습니다.')
}

export function AddressSearch({ label, value, onSelect }: AddressSearchProps) {
  const [isLoading, setIsLoading] = useState(false)
  const [isSearchOpen, setIsSearchOpen] = useState(false)
  const [error, setError] = useState<string>()
  const postcodeContainerRef = useRef<HTMLDivElement>(null)
  const postcodeSdkRef = useRef<typeof daum | undefined>(undefined)
  const isSearchOpenRef = useRef(false)
  const valueRef = useRef(value)
  const onSelectRef = useRef(onSelect)
  valueRef.current = value
  onSelectRef.current = onSelect

  useEffect(() => {
    let active = true
    void loadDaumPostcode().then((postcodeSdk) => {
      if (active) {
        postcodeSdkRef.current = postcodeSdk
      }
    }).catch(() => {
      // 사용자가 검색을 누를 때 다시 시도하고 그때 오류를 표시한다.
    })

    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    window.__turkeyCloseOverlay = () => {
      if (!isSearchOpenRef.current) {
        return false
      }
      closeAddressSearch()
      return true
    }

    return () => {
      delete window.__turkeyCloseOverlay
    }
  }, [])

  useEffect(() => {
    const container = postcodeContainerRef.current
    const postcodeSdk = postcodeSdkRef.current
    if (!isSearchOpen || !container || !postcodeSdk) {
      return
    }

    container.replaceChildren()
    new postcodeSdk.Postcode({ oncomplete: handleAddressComplete }).embed(container)
  }, [isSearchOpen])

  function handleAddressComplete(data: daum.PostcodeData) {
    closeAddressSearch()
    const roadAddress = data.roadAddress || data.autoRoadAddress || data.address
    const selectedAddress = {
      ...valueRef.current,
      roadAddress,
      postalCode: data.zonecode,
      latitude: '',
      longitude: '',
    }
    onSelectRef.current(selectedAddress)
    setIsLoading(true)
    void geocodeAddress(getGeocodeCandidates(data))
      .then((coordinates) => {
        onSelectRef.current({
          ...selectedAddress,
          ...coordinates,
        })
      })
      .catch((geocodeError: unknown) => {
        setError(geocodeError instanceof Error ? geocodeError.message : '좌표 변환에 실패했습니다.')
      })
      .finally(() => setIsLoading(false))
  }

  function closeAddressSearch() {
    isSearchOpenRef.current = false
    setIsSearchOpen(false)
  }

  async function openAddressSearch() {
    setError(undefined)
    setIsLoading(true)

    try {
      postcodeSdkRef.current ??= await loadDaumPostcode()
      isSearchOpenRef.current = true
      setIsSearchOpen(true)
      setIsLoading(false)
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
        disabled={isLoading || isSearchOpen}
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
      {isSearchOpen && (
        <div className="fixed inset-0 z-[100] flex flex-col bg-white" role="dialog" aria-modal="true" aria-label={`${label} 주소 검색`}>
          <header className="flex h-14 shrink-0 items-center justify-between border-b border-gray-200 px-4">
            <h2 className="text-base font-bold">{label} 주소 검색</h2>
            <button type="button" onClick={closeAddressSearch} className="p-2" aria-label="주소 검색 닫기">
              <span className="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </header>
          <div ref={postcodeContainerRef} className="min-h-0 flex-1" />
        </div>
      )}
    </div>
  )
}
