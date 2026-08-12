import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { isAxiosError } from 'axios'
import { useNavigate } from '@tanstack/react-router'
import {
  useCreateCustomerDelivery,
  useQuoteFare,
} from '@/api/generated/customer-delivery/customer-delivery'
import type {
  DeliveryCreateRequestItemType,
  FareQuoteRequest,
} from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import {
  INITIAL_DELIVERY_FORM_VALUES,
  hasQuoteInputErrors,
  toDeliveryCreateRequest,
  toFareQuoteRequest,
  validateDeliveryForm,
  type AddressField,
  type ContactField,
  type DeliveryFormErrors,
  type DeliveryFormValues,
} from '../-deliveryForm'
import { AddressSearch } from './AddressSearch'

const ITEM_OPTIONS: Array<{
  value: DeliveryCreateRequestItemType
  label: string
  description: string
}> = [
  { value: 'DOCUMENT', label: '초소형', description: '문서 · 휴대폰 등 2kg 이하' },
  { value: 'SMALL_PARCEL', label: '소형', description: 'A4 박스 등 5kg 이하' },
  { value: 'MEDIUM_PARCEL', label: '중형', description: '라면 박스 등 20kg 이하' },
  { value: 'FOOD', label: '음식', description: '포장된 음식' },
]

const inputClassName = 'w-full rounded-lg border border-gray-200 bg-gray-50 p-3 text-sm focus:border-blue-500 focus:outline-none'

function formatFare(fare: number | undefined): string {
  return fare == null ? '견적 전' : `${fare.toLocaleString('ko-KR')}원`
}

function apiErrorMessage(error: unknown, fallback: string): string {
  if (!isAxiosError<{ message?: string }>(error)) {
    return fallback
  }
  return error.response?.data?.message || fallback
}

function isOngoingDeliveryConflict(error: unknown): boolean {
  return isAxiosError<{ message?: string }>(error)
    && error.response?.status === 409
    && (error.response.data?.message?.includes('진행 중') ?? false)
}

type AddressFieldsProps = {
  legend: string
  prefix: 'pickup' | 'destination'
  value: AddressField
  errors: DeliveryFormErrors
  onChange: (value: AddressField) => void
}

function AddressFields({ legend, prefix, value, errors, onChange }: AddressFieldsProps) {
  function change(field: keyof AddressField, nextValue: string) {
    onChange({ ...value, [field]: nextValue })
  }

  function errorFor(field: keyof AddressField) {
    return errors[`${prefix}.${field}` as keyof DeliveryFormErrors]
  }

  return (
    <fieldset className="space-y-3 rounded-xl border border-gray-200 p-4">
      <legend className="px-2 text-sm font-bold text-gray-700">{legend}</legend>
      <Field label="도로명 주소" error={errorFor('roadAddress') ?? errorFor('postalCode')}>
        <AddressSearch label={legend} value={value} onSelect={onChange} />
      </Field>
      <Field label="상세 주소">
        <input
          value={value.detailAddress}
          onChange={(event) => change('detailAddress', event.target.value)}
          className={inputClassName}
          placeholder="상세주소 (예: 층, 동/호수)"
        />
      </Field>
      {(errorFor('latitude') || errorFor('longitude')) && (
        <p className="text-xs text-red-600">주소 좌표를 다시 확인해 주세요.</p>
      )}
    </fieldset>
  )
}

type ContactFieldsProps = {
  legend: string
  prefix: 'sender' | 'recipient'
  value: ContactField
  errors: DeliveryFormErrors
  onChange: (value: ContactField) => void
}

function ContactFields({ legend, prefix, value, errors, onChange }: ContactFieldsProps) {
  return (
    <fieldset className="space-y-3 rounded-xl border border-gray-200 p-4">
      <legend className="px-2 text-sm font-bold text-gray-700">{legend}</legend>
      <Field label="이름" error={errors[`${prefix}.name`]}>
        <input
          value={value.name}
          onChange={(event) => onChange({ ...value, name: event.target.value })}
          className={inputClassName}
          placeholder="이름 *"
        />
      </Field>
      <Field label="연락처" error={errors[`${prefix}.phoneNumber`]}>
        <input
          value={value.phoneNumber}
          onChange={(event) => onChange({ ...value, phoneNumber: event.target.value })}
          className={inputClassName}
          inputMode="tel"
          placeholder="연락처 *"
        />
      </Field>
    </fieldset>
  )
}

function Field({ label, error, children }: {
  label: string
  error?: string
  children: ReactNode
}) {
  return (
    <label className="block text-xs font-medium text-gray-500">
      <span className="mb-1 block">{label}</span>
      {children}
      {error && <span className="mt-1 block text-xs text-red-600">{error}</span>}
    </label>
  )
}

export function DeliveryForm() {
  const navigate = useNavigate()
  const [values, setValues] = useState<DeliveryFormValues>(INITIAL_DELIVERY_FORM_VALUES)
  const [showErrors, setShowErrors] = useState(false)
  const [quotedFare, setQuotedFare] = useState<number>()
  const [estimatedMinutes, setEstimatedMinutes] = useState<number>()
  const [quotedFingerprint, setQuotedFingerprint] = useState<string>()
  const [quoteError, setQuoteError] = useState<string>()
  const [submitError, setSubmitError] = useState<string>()
  const [ongoingConflict, setOngoingConflict] = useState(false)
  const quoteRevision = useRef(0)
  const requestKey = useRef(crypto.randomUUID())

  const quoteMutation = useQuoteFare()
  const createMutation = useCreateCustomerDelivery()
  const errors = useMemo(() => validateDeliveryForm(values), [values])
  const quoteRequest = useMemo<FareQuoteRequest | null>(() => {
    return hasQuoteInputErrors(errors) ? null : toFareQuoteRequest(values)
  }, [errors, values])
  const quoteFingerprint = quoteRequest == null ? undefined : JSON.stringify(quoteRequest)

  useEffect(() => {
    const revision = ++quoteRevision.current
    setQuotedFare(undefined)
    setEstimatedMinutes(undefined)
    setQuotedFingerprint(undefined)
    setQuoteError(undefined)
    setSubmitError(undefined)
    setOngoingConflict(false)

    if (!quoteRequest || !quoteFingerprint) {
      return
    }

    const timeout = window.setTimeout(() => {
      quoteMutation.mutateAsync({ data: quoteRequest })
        .then((response) => {
          if (revision !== quoteRevision.current) {
            return
          }
          const fare = response.data?.fare?.totalFare
          if (fare == null) {
            setQuoteError('견적 응답에 금액이 없습니다. 다시 시도해 주세요.')
            return
          }
          setQuotedFare(fare)
          setEstimatedMinutes(response.data?.estimatedMinutes)
          setQuotedFingerprint(quoteFingerprint)
        })
        .catch((error: unknown) => {
          if (revision === quoteRevision.current) {
            setQuoteError(apiErrorMessage(error, '예상 요금을 불러오지 못했습니다.'))
          }
        })
    }, 400)

    return () => window.clearTimeout(timeout)
  }, [quoteFingerprint])

  const quoteIsCurrent = quotedFare != null && quotedFingerprint === quoteFingerprint
  const hasErrors = Object.keys(errors).length > 0
  const canSubmit = quoteIsCurrent && !hasErrors && !createMutation.isPending

  function updateAddress(field: 'pickup' | 'destination', address: AddressField) {
    setValues((current) => ({ ...current, [field]: address }))
    setSubmitError(undefined)
    setOngoingConflict(false)
  }

  function updateContact(field: 'sender' | 'recipient', contact: ContactField) {
    setValues((current) => ({ ...current, [field]: contact }))
    setSubmitError(undefined)
    setOngoingConflict(false)
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setShowErrors(true)
    setSubmitError(undefined)
    setOngoingConflict(false)

    if (!canSubmit || quotedFare == null) {
      setSubmitError('필수 정보를 입력하고 최신 예상 요금을 확인해 주세요.')
      return
    }

    try {
      const response = await createMutation.mutateAsync({
        data: toDeliveryCreateRequest(values, quotedFare, requestKey.current),
      })
      const deliveryId = response.data?.deliveryId
      if (deliveryId == null) {
        setSubmitError('생성 응답에 배송 식별자가 없습니다. 이용기록을 확인해 주세요.')
        return
      }
      await navigate({
        to: '/customer/deliveries/$deliveryId/tracking',
        params: { deliveryId: String(deliveryId) },
      })
    } catch (error) {
      setOngoingConflict(isOngoingDeliveryConflict(error))
      setSubmitError(apiErrorMessage(error, '배송요청을 생성하지 못했습니다. 입력값은 그대로 유지됩니다.'))
    }
  }

  return (
    <form
      onSubmit={handleSubmit}
      onBlur={() => setShowErrors(true)}
      className="min-h-screen bg-surface pb-28 font-sans text-gray-900"
    >
      <header className="sticky top-0 z-40 flex items-center border-b border-gray-100 bg-white px-4 py-4">
        <button
          type="button"
          aria-label="고객 홈으로 돌아가기"
          onClick={() => navigate({ to: '/customer' })}
          className="-ml-2 rounded-full p-2 text-gray-600 hover:bg-gray-100"
        >
          <svg className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth="1.5" viewBox="0 0 24 24">
            <path d="M10.5 19.5L3 12m0 0l7.5-7.5M3 12h18" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        <h1 className="ml-2 text-lg font-bold">배송요청 만들기</h1>
      </header>

      <main aria-label="배송요청 생성" className="mx-auto max-w-xl bg-white">
        <section className="border-b-8 border-surface px-4 py-6">
          <h2 className="mb-4 text-xl font-bold">물품 정보</h2>
          <div className="grid grid-cols-2 gap-3">
            {ITEM_OPTIONS.map((option) => {
              const selected = values.itemType === option.value
              return (
                <label
                  key={option.value}
                  className={selected
                    ? 'cursor-pointer rounded-xl border border-blue-500 bg-blue-50 p-4'
                    : 'cursor-pointer rounded-xl border border-gray-200 p-4 hover:bg-gray-50'}
                >
                  <input
                    type="radio"
                    name="itemType"
                    value={option.value}
                    checked={selected}
                    onChange={() => setValues((current) => ({ ...current, itemType: option.value }))}
                    className="sr-only"
                  />
                  <span className="block font-bold">{option.label}</span>
                  <span className="mt-1 block text-xs text-gray-500">{option.description}</span>
                </label>
              )
            })}
          </div>
        </section>

        <section className="space-y-4 border-b-8 border-surface px-4 py-6">
          <h2 className="text-xl font-bold">주소 정보</h2>
          <AddressFields
            legend="출발지"
            prefix="pickup"
            value={values.pickup}
            errors={showErrors ? errors : {}}
            onChange={(address) => updateAddress('pickup', address)}
          />
          <AddressFields
            legend="도착지"
            prefix="destination"
            value={values.destination}
            errors={showErrors ? errors : {}}
            onChange={(address) => updateAddress('destination', address)}
          />
        </section>

        <section className="space-y-4 border-b-8 border-surface px-4 py-6">
          <h2 className="text-xl font-bold">연락처</h2>
          <ContactFields
            legend="보내는 분"
            prefix="sender"
            value={values.sender}
            errors={showErrors ? errors : {}}
            onChange={(contact) => updateContact('sender', contact)}
          />
          <ContactFields
            legend="받는 분"
            prefix="recipient"
            value={values.recipient}
            errors={showErrors ? errors : {}}
            onChange={(contact) => updateContact('recipient', contact)}
          />
        </section>

        <section className="border-b-8 border-surface px-4 py-6">
          <div className="mb-3 flex items-center">
            <h2 className="text-lg font-bold">기사님 전달사항</h2>
            <span className="ml-2 text-sm text-gray-400">(선택)</span>
          </div>
          <textarea
            value={values.riderNote}
            onChange={(event) => setValues((current) => ({ ...current, riderNote: event.target.value }))}
            className={`${inputClassName} min-h-24 resize-y`}
            maxLength={255}
            placeholder="기사님께 전달할 내용을 입력하세요."
            aria-describedby="rider-note-help"
          />
          <p id="rider-note-help" className="mt-2 text-xs text-gray-400">
            전달사항은 생성 API 지원 전까지 화면에만 유지됩니다.
          </p>
        </section>

        <section className="space-y-3 px-4 py-6">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium text-gray-500">예상 배송 요금</span>
            <span className="text-xl font-bold">{formatFare(quotedFare)}</span>
          </div>
          {estimatedMinutes != null && (
            <p className="text-right text-sm text-gray-500">예상 소요 시간 {estimatedMinutes}분</p>
          )}
          <p className="text-xs text-gray-500">실제 경로와 정책에 따라 최종 금액이 달라질 수 있습니다.</p>
          {quoteMutation.isPending && <p aria-live="polite" className="text-sm text-blue-600">최신 요금을 계산하고 있어요…</p>}
          {quoteError && <p role="alert" className="text-sm text-red-600">{quoteError}</p>}
          {submitError && (
            <div role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-700">
              <p>{submitError}</p>
              {ongoingConflict && (
                <button
                  type="button"
                  onClick={() => navigate({ to: '/customer/deliveries' })}
                  className="mt-2 font-bold underline"
                >
                  진행 중 배송 확인하기
                </button>
              )}
            </div>
          )}
        </section>
      </main>

      <div className="fixed bottom-0 left-1/2 z-50 box-border w-full max-w-md -translate-x-1/2 rounded-t-2xl bg-white px-4 pb-8 pt-4 shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.05)]">
        <div className="mx-auto max-w-xl">
          <button
            type="submit"
            disabled={!canSubmit}
            className={canSubmit
              ? 'flex w-full items-center justify-center rounded-xl bg-blue-600 py-4 text-lg font-bold text-white'
              : 'flex w-full items-center justify-center rounded-xl bg-gray-100 py-4 text-lg font-bold text-gray-400'}
          >
            {createMutation.isPending ? '배송요청 생성 중…' : `${formatFare(quotedFare)} 결제하기`}
          </button>
        </div>
      </div>
    </form>
  )
}
