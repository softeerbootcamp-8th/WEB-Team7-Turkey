import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import {
  getGetCurrentRiderDeliveryQueryKey,
  useCompleteRiderDelivery,
  useGetCurrentRiderDelivery,
} from '@/api/generated/rider-delivery/rider-delivery'
import { getGetRiderOperatingStatusQueryKey } from '@/api/generated/rider-operating-status/rider-operating-status'
import { getGetRiderSessionQueryKey } from '@/api/generated/rider-session/rider-session'
import { getCompletionErrorMessage, validateCompletionPhoto } from './-completion'

export const Route = createFileRoute('/rider/_authed/delivery/$deliveryId/complete')({
  component: DeliveryCompletion,
})

function DeliveryCompletion() {
  const { deliveryId: deliveryIdParam } = Route.useParams()
  const deliveryId = Number(deliveryIdParam)
  const router = useRouter()
  const queryClient = useQueryClient()
  const inputRef = useRef<HTMLInputElement>(null)
  const [photo, setPhoto] = useState<File | null>(null)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const currentDeliveryQuery = useGetCurrentRiderDelivery({ query: { retry: false } })
  const completionMutation = useCompleteRiderDelivery()
  const delivery = currentDeliveryQuery.data?.data

  useEffect(() => () => {
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl)
    }
  }, [previewUrl])

  function openCamera() {
    if (completionMutation.isPending) {
      return
    }
    if (inputRef.current) {
      inputRef.current.value = ''
      inputRef.current.click()
    }
  }

  function selectPhoto(event: ChangeEvent<HTMLInputElement>) {
    const selectedPhoto = event.target.files?.[0]
    if (!selectedPhoto) {
      return
    }

    const validationMessage = validateCompletionPhoto(selectedPhoto)
    if (validationMessage) {
      setErrorMessage(validationMessage)
      return
    }

    setPhoto(selectedPhoto)
    setPreviewUrl(URL.createObjectURL(selectedPhoto))
    setErrorMessage(null)
  }

  function goBack() {
    void router.navigate({ to: '/rider/delivery' })
  }

  function completeDelivery() {
    if (!photo || !Number.isSafeInteger(deliveryId) || completionMutation.isPending) {
      return
    }

    setErrorMessage(null)
    completionMutation.mutate(
      {
        deliveryId,
        data: { file: photo },
        params: { proofType: 'PHOTO' },
      },
      {
        onSuccess: async () => {
          queryClient.removeQueries({ queryKey: getGetCurrentRiderDeliveryQueryKey() })
          queryClient.removeQueries({ queryKey: getGetRiderOperatingStatusQueryKey() })
          queryClient.removeQueries({ queryKey: getGetRiderSessionQueryKey() })
          await router.invalidate()
          await router.navigate({ to: '/rider', replace: true })
        },
        onError: (error) => {
          setErrorMessage(getCompletionErrorMessage(error))
          void currentDeliveryQuery.refetch()
        },
      },
    )
  }

  const invalidDelivery = !Number.isSafeInteger(deliveryId)
    || (delivery !== undefined && (delivery.deliveryId !== deliveryId || delivery.status !== 'DELIVERING'))

  if (currentDeliveryQuery.isPending) {
    return <CompletionFeedback message="배송 정보를 확인하고 있어요." spin />
  }

  if (currentDeliveryQuery.isError) {
    return (
      <CompletionFeedback
        message="배송 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
        onRetry={() => void currentDeliveryQuery.refetch()}
        onBack={goBack}
      />
    )
  }

  if (!delivery || invalidDelivery) {
    return <CompletionFeedback message="완료 인증이 필요한 배송을 찾을 수 없습니다." onBack={goBack} />
  }

  return (
    <main aria-label="배송 완료 인증" className="mx-auto flex min-h-screen w-full max-w-md flex-col bg-background text-on-background shadow-[0_0_10px_rgba(0,0,0,0.08)]">
      <header className="sticky top-0 z-20 flex h-16 items-center border-b border-surface-container bg-surface px-container-margin">
        <button
          type="button"
          aria-label="진행 중 배송으로 돌아가기"
          onClick={goBack}
          disabled={completionMutation.isPending}
          className="-ml-2 flex h-10 w-10 items-center justify-center rounded-full text-on-surface transition-colors hover:bg-surface-container disabled:opacity-50"
        >
          <span className="material-symbols-outlined" aria-hidden="true">arrow_back</span>
        </button>
        <h1 className="flex-1 text-center font-headline-md text-headline-md font-bold text-on-surface">배송 완료 인증</h1>
        <div className="w-8" aria-hidden="true" />
      </header>

      <section className="flex flex-1 flex-col px-container-margin py-xl pb-28">
        <div className="text-center">
          <p className="font-label-sm text-label-sm text-secondary">배송 #{deliveryId}</p>
          <h2 className="mt-2 font-headline-md text-headline-md font-bold text-on-surface">전달 완료 사진을 촬영해 주세요</h2>
          <p className="mt-2 font-body-md text-body-md text-secondary">물품이 안전하게 전달된 모습이 잘 보이도록 촬영해 주세요.</p>
        </div>

        <input
          ref={inputRef}
          type="file"
          accept="image/*"
          capture="environment"
          onChange={selectPhoto}
          className="sr-only"
          aria-label="배송 완료 사진 촬영"
        />

        <button
          type="button"
          onClick={openCamera}
          disabled={completionMutation.isPending}
          aria-label={previewUrl ? '배송 완료 사진 다시 촬영하기' : '카메라 열기'}
          className="relative mt-xl flex min-h-80 w-full flex-1 items-center justify-center overflow-hidden rounded-2xl border-2 border-dashed border-primary-container bg-surface-container-lowest text-on-surface shadow-sm transition-colors hover:bg-surface-container-low disabled:cursor-not-allowed disabled:opacity-60"
        >
          {previewUrl ? (
            <>
              <img src={previewUrl} alt="촬영한 배송 완료 인증 사진" className="absolute inset-0 h-full w-full object-cover" />
              <span className="absolute bottom-4 rounded-full bg-on-surface/75 px-4 py-2 font-label-md text-label-md text-surface">
                다시 촬영하기
              </span>
            </>
          ) : (
            <span className="flex flex-col items-center gap-4 px-6 text-center">
              <span className="flex h-20 w-20 items-center justify-center rounded-full bg-primary-container text-on-primary-container shadow-sm">
                <span className="material-symbols-outlined text-4xl" aria-hidden="true">photo_camera</span>
              </span>
              <span>
                <span className="block font-headline-md text-headline-md font-bold">카메라 열기</span>
                <span className="mt-1 block font-body-md text-body-md text-secondary">휴대폰 카메라로 인증 사진을 촬영합니다.</span>
              </span>
            </span>
          )}
        </button>

        {errorMessage && (
          <p role="alert" className="mt-md rounded-xl bg-error-container px-4 py-3 font-body-md text-body-md text-on-error-container">
            {errorMessage}
          </p>
        )}
      </section>

      <footer className="fixed inset-x-0 bottom-0 z-30 mx-auto grid h-20 w-full max-w-md grid-cols-2 gap-gutter border-t border-surface-container bg-surface px-container-margin py-3 shadow-[0_-4px_12px_rgba(0,0,0,0.08)]">
        <button
          type="button"
          onClick={goBack}
          disabled={completionMutation.isPending}
          className="rounded-xl border border-surface-container bg-surface-container-lowest font-label-lg text-label-lg font-bold text-on-surface transition-colors hover:bg-surface-container-low disabled:opacity-50"
        >
          뒤로 가기
        </button>
        <button
          type="button"
          onClick={completeDelivery}
          disabled={!photo || completionMutation.isPending}
          aria-busy={completionMutation.isPending}
          className="rounded-xl bg-primary-container font-label-lg text-label-lg font-bold text-on-primary-container transition-colors hover:bg-primary-fixed disabled:cursor-not-allowed disabled:opacity-50"
        >
          {completionMutation.isPending ? '인증 중…' : '인증하기'}
        </button>
      </footer>
    </main>
  )
}

function CompletionFeedback({
  message,
  spin = false,
  onRetry,
  onBack,
}: {
  message: string
  spin?: boolean
  onRetry?: () => void
  onBack?: () => void
}) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-background px-container-margin text-center">
      <div className="flex max-w-sm flex-col items-center gap-4">
        <span className={`material-symbols-outlined text-5xl text-secondary ${spin ? 'animate-spin' : ''}`} aria-hidden="true">
          {spin ? 'progress_activity' : 'photo_camera_back'}
        </span>
        <p role={onRetry ? 'alert' : 'status'} className="font-body-lg text-body-lg text-on-surface">{message}</p>
        <div className="flex gap-3">
          {onBack && (
            <button type="button" onClick={onBack} className="rounded-xl border border-surface-container px-5 py-3 font-label-lg text-on-surface">
              뒤로 가기
            </button>
          )}
          {onRetry && (
            <button type="button" onClick={onRetry} className="rounded-xl bg-primary-container px-5 py-3 font-label-lg text-on-primary-container">
              다시 시도
            </button>
          )}
        </div>
      </div>
    </main>
  )
}
