import { useState } from 'react'
import { createFileRoute, Link, useRouter } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import {
  getGetCustomerPointBalanceQueryKey,
  getGetCustomerPointTransactionsQueryKey,
  useCancelCustomerPointCharge,
  useConfirmCustomerPointCharge,
  useGetCustomerPointBalance,
  useRequestCustomerPointCharge,
} from '@/api/generated/customer-point/customer-point'
import type { PointChargeRequestPaymentMethod } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import {
  CHARGE_AMOUNT_OPTIONS,
  PAYMENT_METHOD_OPTIONS,
  addChargeAmount,
  createMockPaymentAuthToken,
  formatChargeAmountInput,
  getChargeAmountError,
} from './-charge'
import { formatCustomerPoints, getCustomerPointErrorMessage } from './-customerPoints'
import { MockPaymentModal } from './-components/MockPaymentModal'

export const Route = createFileRoute('/customer/_authed/points/charge')({ component: PointsCharge })

type ChargeAttempt = {
  requestKey: string
  amount: number
  paymentMethod: PointChargeRequestPaymentMethod
  pointChargeId?: number
}

type ChargeResult = {
  amount: number
  balanceAfter?: number
}

function PointsCharge() {
  const router = useRouter()
  const queryClient = useQueryClient()
  const [amountInput, setAmountInput] = useState('')
  const [paymentMethod, setPaymentMethod] = useState<PointChargeRequestPaymentMethod>('CARD')
  const [attempt, setAttempt] = useState<ChargeAttempt | null>(null)
  const [result, setResult] = useState<ChargeResult | null>(null)
  const [flowError, setFlowError] = useState<string | null>(null)
  const [requiresReview, setRequiresReview] = useState(false)
  const [paymentModalOpen, setPaymentModalOpen] = useState(false)
  const balanceQuery = useGetCustomerPointBalance({ query: { retry: false } })
  const requestMutation = useRequestCustomerPointCharge()
  const confirmMutation = useConfirmCustomerPointCharge()
  const cancelMutation = useCancelCustomerPointCharge()
  const amount = Number(amountInput || 0)
  const amountError = getChargeAmountError(amount)
  const isProcessing = requestMutation.isPending || confirmMutation.isPending

  function resetAttempt() {
    if (isProcessing) return
    setAttempt(null)
    setFlowError(null)
    setRequiresReview(false)
    requestMutation.reset()
    confirmMutation.reset()
    cancelMutation.reset()
  }

  async function refreshPointQueries() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: getGetCustomerPointBalanceQueryKey() }),
      queryClient.invalidateQueries({ queryKey: getGetCustomerPointTransactionsQueryKey() }),
    ])
  }

  async function prepareCharge() {
    if (amountError || isProcessing || requiresReview) return
    setFlowError(null)

    let currentAttempt =
      attempt && attempt.amount === amount && attempt.paymentMethod === paymentMethod
        ? attempt
        : { requestKey: crypto.randomUUID(), amount, paymentMethod }
    setAttempt(currentAttempt)

    try {
      let pointChargeId = currentAttempt.pointChargeId
      if (pointChargeId == null) {
        const requestResponse = await requestMutation.mutateAsync({
          data: {
            chargeRequestKey: currentAttempt.requestKey,
            amount: currentAttempt.amount,
            paymentMethod: currentAttempt.paymentMethod,
          },
        })
        const requestedCharge = requestResponse.data

        if (requestedCharge?.status === 'PAID') {
          await refreshPointQueries()
          setResult({ amount: requestedCharge.requestedAmount ?? currentAttempt.amount })
          setAttempt(null)
          return
        }
        if (requestedCharge?.status !== 'PENDING' || requestedCharge.pointChargeId == null) {
          throw new Error('충전 요청 응답이 올바르지 않습니다.')
        }

        pointChargeId = requestedCharge.pointChargeId
        currentAttempt = { ...currentAttempt, pointChargeId }
        setAttempt(currentAttempt)
      }

      setPaymentModalOpen(true)
    } catch (error) {
      setFlowError(
        getCustomerPointErrorMessage(
          error,
          error instanceof Error && error.message
            ? error.message
            : '결제창을 열지 못했습니다. 잠시 후 다시 시도해 주세요.',
        ),
      )
    }
  }

  async function confirmCharge() {
    if (!attempt?.pointChargeId || confirmMutation.isPending || requiresReview) return
    setFlowError(null)

    try {
      const confirmResponse = await confirmMutation.mutateAsync({
        pointChargeId: attempt.pointChargeId,
        data: {
          amount: attempt.amount,
          authToken: createMockPaymentAuthToken(),
        },
      })
      const confirmedCharge = confirmResponse.data

      if (confirmedCharge?.status === 'FAILED') {
        setAttempt(null)
        setPaymentModalOpen(false)
        setFlowError('결제가 승인되지 않았습니다. 결제수단을 확인한 뒤 다시 시도해 주세요.')
        return
      }
      if (confirmedCharge?.status !== 'PAID') {
        throw new Error('결제 승인 응답이 올바르지 않습니다.')
      }

      await refreshPointQueries()
      setResult({
        amount: confirmedCharge.approvedAmount ?? attempt.amount,
        balanceAfter: confirmedCharge.balanceAfter,
      })
      setPaymentModalOpen(false)
      setAttempt(null)
    } catch (error) {
      const status = isAxiosError(error) ? error.response?.status : undefined
      // confirm 이후 502는 PG 승인 여부가 불명이고, 409는 승인 식별자가 이미 기록된 대사 대상일 수
      // 있다. 이 상태에서 같은 confirm이나 새 충전을 반복하면 중복 결제 위험이 있으므로 자동 재시도를
      // 제공하지 않는다. 준비 단계의 409는 잔액이 움직이지 않았으므로 같은 멱등키 재시도가 가능하다.
      if (status === 502 || status === 409) {
        setRequiresReview(true)
      }
      setFlowError(
        getCustomerPointErrorMessage(
          error,
          error instanceof Error && error.message
            ? error.message
            : '포인트를 충전하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        ),
      )
    }
  }

  async function cancelPreparedCharge() {
    if (!attempt?.pointChargeId || confirmMutation.isPending || cancelMutation.isPending) return

    if (requiresReview) {
      setPaymentModalOpen(false)
      return
    }

    try {
      await cancelMutation.mutateAsync({ pointChargeId: attempt.pointChargeId })
      setPaymentModalOpen(false)
      setAttempt(null)
      setFlowError(null)
      requestMutation.reset()
      confirmMutation.reset()
    } catch (error) {
      setFlowError(
        getCustomerPointErrorMessage(
          error,
          '결제 요청을 취소하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        ),
      )
    }
  }

  if (result) {
    return (
      <div className="mx-auto flex min-h-screen w-full max-w-md flex-col bg-surface-container-lowest text-on-surface shadow-sm">
        <main className="flex flex-1 flex-col items-center justify-center px-6 py-12 text-center">
          <span
            aria-hidden="true"
            className="material-symbols-outlined flex h-20 w-20 items-center justify-center rounded-full bg-tertiary-container text-5xl text-on-tertiary-container"
          >
            check_circle
          </span>
          <h1 className="mt-6 text-headline-lg font-bold">충전이 완료됐습니다</h1>
          <p className="mt-2 text-body-lg text-secondary">{formatCustomerPoints(result.amount)}가 충전됐어요.</p>
          {result.balanceAfter != null && (
            <dl className="mt-8 flex w-full items-center justify-between rounded-2xl bg-surface-container-low px-5 py-4">
              <dt className="text-body-md text-secondary">충전 후 잔액</dt>
              <dd className="text-headline-sm font-bold">{formatCustomerPoints(result.balanceAfter)}</dd>
            </dl>
          )}
          <Link
            to="/customer/points"
            className="mt-8 flex min-h-12 w-full items-center justify-center rounded-xl bg-primary-container text-label-lg font-bold text-on-primary-container"
          >
            포인트 내역 확인하기
          </Link>
          <button
            type="button"
            onClick={() => {
              setResult(null)
              setFlowError(null)
              setRequiresReview(false)
              setAmountInput('')
            }}
            className="mt-3 min-h-12 w-full rounded-xl border border-outline-variant text-label-lg font-bold"
          >
            추가로 충전하기
          </button>
        </main>
      </div>
    )
  }

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-md flex-col bg-surface text-on-surface shadow-sm">
      <header className="sticky top-0 z-20 flex h-16 items-center border-b border-outline-variant bg-surface-container-lowest px-4">
        <button
          type="button"
          aria-label="포인트 화면으로 돌아가기"
          onClick={() => void router.navigate({ to: '/customer/points' })}
          disabled={isProcessing}
          className="flex h-11 w-11 items-center justify-center rounded-full transition-colors hover:bg-surface-container-low disabled:opacity-40"
        >
          <span aria-hidden="true" className="material-symbols-outlined">arrow_back</span>
        </button>
        <h1 className="flex-1 text-center text-headline-md font-bold">포인트 충전</h1>
        <div className="h-11 w-11" aria-hidden="true" />
      </header>

      <main className="flex flex-1 flex-col gap-2 bg-surface-container-low pb-28">
        <section className="bg-surface-container-lowest px-5 py-5">
          <div className="flex items-center justify-between rounded-2xl bg-primary-container px-5 py-4 text-on-primary-container">
            <div>
              <p className="text-body-md">현재 보유 포인트</p>
              <p className="mt-1 text-headline-md font-bold">
                {balanceQuery.isPending
                  ? '조회 중…'
                  : balanceQuery.data?.data?.balance != null
                    ? formatCustomerPoints(balanceQuery.data.data.balance)
                    : '잔액 미확인'}
              </p>
            </div>
            <span aria-hidden="true" className="material-symbols-outlined text-3xl">account_balance_wallet</span>
          </div>
          {balanceQuery.isError && (
            <p role="alert" className="mt-2 text-label-md text-error">
              잔액을 불러오지 못했지만 충전은 계속할 수 있습니다.
            </p>
          )}
        </section>

        <section aria-labelledby="charge-amount-title" className="bg-surface-container-lowest px-5 py-6">
          <h2 id="charge-amount-title" className="text-headline-sm font-bold">충전 금액</h2>
          <p className="mt-1 text-body-md text-secondary">금액을 직접 입력하거나 아래 버튼으로 추가해 주세요.</p>
          <label htmlFor="charge-amount" className="sr-only">충전할 포인트</label>
          <div className="relative mt-4">
            <input
              id="charge-amount"
              type="text"
              inputMode="numeric"
              autoComplete="off"
              value={formatChargeAmountInput(amountInput)}
              disabled={isProcessing || requiresReview}
              placeholder="0"
              aria-invalid={Boolean(amountInput && amountError)}
              onChange={(event) => {
                const digits = event.target.value.replace(/\D/g, '').slice(0, 7)
                setAmountInput(digits)
                resetAttempt()
              }}
              className="h-14 w-full rounded-2xl border border-outline-variant bg-surface-container-lowest px-4 pr-12 text-right text-headline-sm font-bold focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary-container disabled:opacity-50"
            />
            <span className="absolute right-4 top-1/2 -translate-y-1/2 text-body-lg font-bold text-secondary">P</span>
          </div>
          <div className="mt-3 grid grid-cols-4 gap-2" aria-label="충전 금액 빠른 추가">
            {CHARGE_AMOUNT_OPTIONS.map((option) => (
              <button
                key={option}
                type="button"
                disabled={isProcessing || requiresReview}
                onClick={() => {
                  setAmountInput(String(addChargeAmount(amount, option)))
                  resetAttempt()
                }}
                className="min-h-11 rounded-xl border border-outline-variant bg-surface-container-lowest px-1 text-label-md font-bold text-on-surface transition-colors hover:bg-primary-container disabled:opacity-50"
              >
                +{option / 10_000}만
              </button>
            ))}
          </div>
          {amountInput && amountError && (
            <p role="alert" className="mt-2 text-label-md text-error">{amountError}</p>
          )}
        </section>

        <section aria-labelledby="payment-method-title" className="bg-surface-container-lowest px-5 py-6">
          <h2 id="payment-method-title" className="text-headline-sm font-bold">결제수단</h2>
          <div className="mt-4 overflow-hidden rounded-2xl border border-outline-variant">
            {PAYMENT_METHOD_OPTIONS.map((option, index) => (
              <label
                key={option.value}
                className={`flex cursor-pointer items-center gap-4 p-4 ${index > 0 ? 'border-t border-outline-variant' : ''} ${paymentMethod === option.value ? 'bg-primary-container/40' : 'bg-surface-container-lowest'}`}
              >
                <input
                  type="radio"
                  name="payment-method"
                  value={option.value}
                  checked={paymentMethod === option.value}
                  disabled={isProcessing || requiresReview}
                  onChange={() => {
                    setPaymentMethod(option.value)
                    resetAttempt()
                  }}
                  className="h-5 w-5 accent-primary"
                />
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined flex h-10 w-10 items-center justify-center rounded-full bg-surface-container-low text-on-surface"
                >
                  {option.icon}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-label-lg font-bold">{option.label}</span>
                  <span className="mt-0.5 block text-label-md text-secondary">{option.description}</span>
                </span>
              </label>
            ))}
          </div>
          <p className="mt-3 text-label-md text-secondary">
            현재는 실제 결제가 발생하지 않는 모의 결제 환경입니다.
          </p>
        </section>

        {flowError && (
          <section role="alert" className="mx-5 rounded-2xl border border-error bg-error-container/30 p-4">
            <div className="flex items-start gap-3">
              <span aria-hidden="true" className="material-symbols-outlined text-error">error</span>
              <div className="min-w-0">
                <p className="text-label-lg font-bold text-error">충전을 완료하지 못했습니다</p>
                <p className="mt-1 text-body-md text-on-surface">{flowError}</p>
                {requiresReview ? (
                  <p className="mt-2 text-label-sm text-secondary">
                    결제 승인 여부를 확인할 수 없는 상태입니다. 중복 결제를 막기 위해 다시 승인하지 않습니다.
                  </p>
                ) : attempt?.pointChargeId != null ? (
                  <p className="mt-2 text-label-sm text-secondary">
                    새 충전 요청을 만들지 않고 기존 승인 단계부터 다시 시도합니다.
                  </p>
                ) : null}
              </div>
            </div>
          </section>
        )}
      </main>

      <div className="fixed bottom-0 left-1/2 z-30 box-border w-full max-w-md -translate-x-1/2 border-t border-outline-variant bg-surface-container-lowest px-4 pb-[max(1rem,env(safe-area-inset-bottom))] pt-4">
        <div className="w-full min-w-0">
          <button
            type="button"
            disabled={Boolean(amountError) || isProcessing || cancelMutation.isPending || requiresReview}
            aria-busy={isProcessing}
            onClick={() => void prepareCharge()}
            className="flex min-h-14 w-full items-center justify-center rounded-xl bg-primary-container px-5 text-headline-sm font-bold text-on-primary-container transition-transform active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50"
          >
            {requiresReview
              ? '결제 상태 확인 필요'
              : requestMutation.isPending
              ? '충전 요청 생성 중…'
              : attempt?.pointChargeId != null
                ? '결제창 다시 열기'
                : `${formatCustomerPoints(amount)} 결제하기`}
          </button>
        </div>
      </div>

      {paymentModalOpen && attempt?.pointChargeId != null && (
        <MockPaymentModal
          amount={attempt.amount}
          paymentMethod={attempt.paymentMethod}
          confirmPending={confirmMutation.isPending}
          cancelPending={cancelMutation.isPending}
          error={flowError}
          requiresReview={requiresReview}
          onConfirm={confirmCharge}
          onCancel={cancelPreparedCharge}
        />
      )}
    </div>
  )
}
