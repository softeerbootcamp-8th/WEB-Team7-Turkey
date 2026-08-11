import { useEffect, useState } from 'react'
import type { PointChargeRequestPaymentMethod } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { formatCustomerPoints } from '../-customerPoints'

const methodLabels: Record<PointChargeRequestPaymentMethod, string> = {
  CARD: '신용·체크카드',
  BANK_TRANSFER: '계좌이체',
}

export function MockPaymentModal({
  amount,
  paymentMethod,
  confirmPending,
  cancelPending,
  error,
  requiresReview,
  onConfirm,
  onCancel,
}: {
  amount: number
  paymentMethod: PointChargeRequestPaymentMethod
  confirmPending: boolean
  cancelPending: boolean
  error: string | null
  requiresReview: boolean
  onConfirm: () => Promise<void>
  onCancel: () => Promise<void>
}) {
  const [agreed, setAgreed] = useState(false)
  const [authorizing, setAuthorizing] = useState(false)
  const [cardNumber, setCardNumber] = useState('')
  const [cardExpiry, setCardExpiry] = useState('')
  const [cardCvc, setCardCvc] = useState('')
  const [cardPassword, setCardPassword] = useState('')
  const [bank, setBank] = useState('')
  const [accountNumber, setAccountNumber] = useState('')
  const [accountPassword, setAccountPassword] = useState('')
  const busy = authorizing || confirmPending || cancelPending
  const paymentDetailsComplete = paymentMethod === 'CARD'
    ? Boolean(cardNumber && cardExpiry && cardCvc && cardPassword)
    : Boolean(bank && accountNumber && accountPassword)

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
    }
  }, [])

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && !busy) void onCancel()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [busy, onCancel])

  async function confirmPayment() {
    if (!paymentDetailsComplete || !agreed || busy || requiresReview) return
    setAuthorizing(true)
    // 실제 PG 결제창이 카드 인증을 처리하는 짧은 구간을 모의한다. confirm API는 인증이 끝난 뒤 호출한다.
    await new Promise((resolve) => window.setTimeout(resolve, 550))
    setAuthorizing(false)
    await onConfirm()
  }

  return (
    <div
      role="presentation"
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/55 px-0 backdrop-blur-[2px] sm:items-center sm:px-5"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !busy) void onCancel()
      }}
    >
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="mock-payment-title"
        aria-describedby="mock-payment-description"
        className="max-h-[94dvh] w-full max-w-md overflow-y-auto rounded-t-[28px] bg-white text-[#191f28] shadow-2xl [clip-path:inset(0_round_28px)] [scrollbar-color:#c7cdd4_transparent] [scrollbar-width:thin] [&::-webkit-scrollbar]:w-1.5 [&::-webkit-scrollbar-thumb]:rounded-full [&::-webkit-scrollbar-thumb]:bg-[#c7cdd4] [&::-webkit-scrollbar-track]:bg-transparent sm:rounded-[28px]"
      >
        <div className="flex items-center justify-between px-6 pb-2 pt-5">
          <div className="flex items-center gap-2">
            <span className="flex h-8 w-8 items-center justify-center rounded-xl bg-[#3182f6] text-sm font-black text-white">
              C
            </span>
            <span className="text-[17px] font-bold tracking-tight">Chicken Payment</span>
          </div>
          <button
            type="button"
            aria-label="결제창 닫기"
            disabled={busy}
            onClick={() => void onCancel()}
            className="flex h-10 w-10 items-center justify-center rounded-full text-[#6b7684] transition-colors hover:bg-[#f2f4f6] disabled:opacity-40"
          >
            <span aria-hidden="true" className="material-symbols-outlined">close</span>
          </button>
        </div>

        <div className="px-6 pb-7">
          <div className="mt-3 rounded-2xl bg-[#f2f4f6] px-5 py-4">
            <p id="mock-payment-description" className="text-sm font-medium text-[#6b7684]">Quick 포인트 충전</p>
            <p className="mt-1 text-[28px] font-bold tracking-[-0.03em]">{formatCustomerPoints(amount)}</p>
          </div>

          <div className="mt-7">
            <h2 id="mock-payment-title" className="text-xl font-bold tracking-[-0.02em]">결제 정보를 확인해 주세요</h2>
            <dl className="mt-5 space-y-4 text-[15px]">
              <div className="flex items-center justify-between gap-4">
                <dt className="text-[#6b7684]">결제수단</dt>
                <dd className="font-semibold">{methodLabels[paymentMethod]}</dd>
              </div>
              <div className="flex items-center justify-between gap-4">
                <dt className="text-[#6b7684]">상품명</dt>
                <dd className="font-semibold">포인트 {amount.toLocaleString('ko-KR')}P</dd>
              </div>
              <div className="flex items-center justify-between gap-4 border-t border-[#e5e8eb] pt-4">
                <dt className="font-semibold">총 결제금액</dt>
                <dd className="text-lg font-bold text-[#3182f6]">{amount.toLocaleString('ko-KR')}원</dd>
              </div>
            </dl>
          </div>

          <div className="mt-7 border-t border-[#e5e8eb] pt-6">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h3 className="text-[17px] font-bold">{methodLabels[paymentMethod]} 정보</h3>
                <p className="mt-1 text-xs leading-5 text-[#8b95a1]">
                  테스트용 입력입니다. 실제 카드·계좌 정보를 입력하지 마세요.
                </p>
              </div>
              <span className="shrink-0 rounded-full bg-[#e8f3ff] px-2.5 py-1 text-[11px] font-bold text-[#3182f6]">
                MOCK
              </span>
            </div>

            {paymentMethod === 'CARD' ? (
              <div className="mt-4 grid grid-cols-2 gap-3">
                <PaymentField className="col-span-2" label="카드번호">
                  <input
                    type="text"
                    inputMode="numeric"
                    autoComplete="off"
                    value={cardNumber}
                    placeholder="0000 0000 0000 0000"
                    disabled={busy || requiresReview}
                    onChange={(event) => {
                      const digits = event.target.value.replace(/\D/g, '').slice(0, 16)
                      setCardNumber(digits.replace(/(\d{4})(?=\d)/g, '$1 '))
                    }}
                    className="mock-payment-input"
                  />
                </PaymentField>
                <PaymentField label="유효기간">
                  <input
                    type="text"
                    inputMode="numeric"
                    autoComplete="off"
                    value={cardExpiry}
                    placeholder="MM/YY"
                    disabled={busy || requiresReview}
                    onChange={(event) => {
                      const digits = event.target.value.replace(/\D/g, '').slice(0, 4)
                      setCardExpiry(digits.length > 2 ? `${digits.slice(0, 2)}/${digits.slice(2)}` : digits)
                    }}
                    className="mock-payment-input"
                  />
                </PaymentField>
                <PaymentField label="CVC">
                  <input
                    type="password"
                    inputMode="numeric"
                    autoComplete="new-password"
                    value={cardCvc}
                    placeholder="뒷면 3자리"
                    disabled={busy || requiresReview}
                    onChange={(event) => setCardCvc(event.target.value.replace(/\D/g, '').slice(0, 3))}
                    className="mock-payment-input"
                  />
                </PaymentField>
                <PaymentField className="col-span-2" label="카드 비밀번호">
                  <input
                    type="password"
                    inputMode="numeric"
                    autoComplete="new-password"
                    value={cardPassword}
                    placeholder="앞 2자리"
                    disabled={busy || requiresReview}
                    onChange={(event) => setCardPassword(event.target.value.replace(/\D/g, '').slice(0, 2))}
                    className="mock-payment-input"
                  />
                </PaymentField>
              </div>
            ) : (
              <div className="mt-4 space-y-3">
                <PaymentField label="은행">
                  <select
                    value={bank}
                    disabled={busy || requiresReview}
                    onChange={(event) => setBank(event.target.value)}
                    className="mock-payment-input"
                  >
                    <option value="">은행 선택</option>
                    <option value="KB">KB국민은행</option>
                    <option value="SHINHAN">신한은행</option>
                    <option value="WOORI">우리은행</option>
                    <option value="HANA">하나은행</option>
                    <option value="NH">NH농협은행</option>
                  </select>
                </PaymentField>
                <PaymentField label="계좌번호">
                  <input
                    type="text"
                    inputMode="numeric"
                    autoComplete="off"
                    value={accountNumber}
                    placeholder="숫자만 입력"
                    disabled={busy || requiresReview}
                    onChange={(event) => setAccountNumber(event.target.value.replace(/\D/g, '').slice(0, 20))}
                    className="mock-payment-input"
                  />
                </PaymentField>
                <PaymentField label="계좌 비밀번호">
                  <input
                    type="password"
                    inputMode="numeric"
                    autoComplete="new-password"
                    value={accountPassword}
                    placeholder="테스트 값 입력"
                    disabled={busy || requiresReview}
                    onChange={(event) => setAccountPassword(event.target.value.replace(/\D/g, '').slice(0, 8))}
                    className="mock-payment-input"
                  />
                </PaymentField>
              </div>
            )}
            <p className="mt-3 flex items-center gap-1.5 text-xs text-[#8b95a1]">
              <span aria-hidden="true" className="material-symbols-outlined text-base">science</span>
              형식과 관계없이 모든 칸을 채우면 테스트 인증이 완료됩니다.
            </p>
          </div>

          <label className="mt-7 flex cursor-pointer items-start gap-3 rounded-2xl bg-[#f9fafb] p-4">
            <input
              type="checkbox"
              autoFocus
              checked={agreed}
              disabled={busy || requiresReview}
              onChange={(event) => setAgreed(event.target.checked)}
              className="mt-0.5 h-5 w-5 shrink-0 accent-[#3182f6]"
            />
            <span className="text-sm leading-5 text-[#4e5968]">
              주문 내용을 확인했으며, 결제 진행 및 개인정보 제공에 동의합니다.
              <span className="mt-1 block text-xs text-[#8b95a1]">테스트 환경에서는 실제 금액이 결제되지 않습니다.</span>
            </span>
          </label>

          {error && (
            <div role="alert" className="mt-4 rounded-2xl bg-[#fff0f0] px-4 py-3 text-sm leading-5 text-[#e42939]">
              <p className="font-bold">결제를 완료하지 못했습니다</p>
              <p className="mt-1">{error}</p>
              {requiresReview && (
                <p className="mt-1 text-xs">중복 결제 방지를 위해 다시 결제할 수 없습니다.</p>
              )}
            </div>
          )}

          <button
            type="button"
            disabled={!paymentDetailsComplete || !agreed || busy || requiresReview}
            aria-busy={busy}
            onClick={() => void confirmPayment()}
            className="mt-5 flex h-14 w-full items-center justify-center rounded-2xl bg-[#3182f6] px-5 text-[17px] font-bold text-white transition-colors hover:bg-[#1b64da] disabled:bg-[#d1d6db]"
          >
            {cancelPending
              ? '결제 취소 중…'
              : authorizing
                ? '안전하게 인증 중…'
                : confirmPending
                  ? '결제 승인 중…'
                  : requiresReview
                    ? '결제 상태 확인 필요'
                    : `${amount.toLocaleString('ko-KR')}원 결제하기`}
          </button>

          <div className="mt-4 flex items-center justify-center gap-1.5 text-xs font-medium text-[#8b95a1]">
            <span aria-hidden="true" className="material-symbols-outlined text-base">lock</span>
            결제 정보는 안전하게 보호됩니다
          </div>
        </div>
      </section>
    </div>
  )
}

function PaymentField({
  label,
  className = '',
  children,
}: {
  label: string
  className?: string
  children: React.ReactNode
}) {
  return (
    <label className={`block ${className}`}>
      <span className="mb-1.5 block text-xs font-semibold text-[#4e5968]">{label}</span>
      {children}
    </label>
  )
}
