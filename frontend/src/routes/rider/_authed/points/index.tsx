import { useState } from 'react'
import { createFileRoute, useRouter } from '@tanstack/react-router'
import {
  formatPointMonth,
  formatPoints,
  formatPointTransactionDate,
  getPointErrorMessage,
  getPointTransactionLabel,
  getSignedPointAmount,
  getWithdrawalValidation,
  MIN_WITHDRAWAL_AMOUNT,
  pointFilterOptions,
  shiftPointMonth,
  type PointFilter,
  type PointInfoTab,
  type WithdrawalFormValues,
  withdrawalBankOptions,
} from './-riderPoints'
import { useRiderPoints } from './-useRiderPoints'

export const Route = createFileRoute('/rider/_authed/points/')({
  component: RiderPoints,
})

const infoTabs: { value: PointInfoTab; label: string }[] = [
  { value: 'charge-account', label: '충전계좌' },
  { value: 'withdrawal-account', label: '출금계좌' },
  { value: 'guide', label: '가이드' },
]

function RiderPoints() {
  const router = useRouter()
  const [selectedMonth, setSelectedMonth] = useState(() => new Date())
  const [filter, setFilter] = useState<PointFilter>('ALL')
  const [activeTab, setActiveTab] = useState<PointInfoTab>('charge-account')
  const [withdrawalOpen, setWithdrawalOpen] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const points = useRiderPoints(selectedMonth, filter)
  const isLoading = points.balanceQuery.isPending || points.transactionsQuery.isPending
  const error = points.balanceQuery.error ?? points.transactionsQuery.error
  const monthlyTotal = points.transactions.reduce((sum, item) => sum + getSignedPointAmount(item), 0)

  return (
    <main aria-label="라이더 포인트 내역" className="min-h-screen bg-background pb-20 text-on-background">
      <header className="sticky top-0 z-20 flex h-14 items-center bg-surface px-container-margin">
        <button
          type="button"
          aria-label="라이더 홈으로 돌아가기"
          onClick={() => void router.navigate({ to: '/rider' })}
          className="-ml-2 flex h-10 w-10 items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container"
        >
          <span className="material-symbols-outlined" aria-hidden="true">
            arrow_back
          </span>
        </button>
        <h1 className="flex-1 text-center font-headline-md text-on-surface">포인트 내역</h1>
        <div className="w-8" aria-hidden="true" />
      </header>

      <div className="mx-auto w-full max-w-lg">
        <section className="bg-surface-container-lowest px-container-margin py-lg">
          <div className="flex items-end justify-between gap-md">
            <div>
              <p className="font-body-md text-secondary">출금 가능 포인트</p>
              <p className="mt-1 flex items-center gap-2 font-headline-lg text-on-surface">
                {points.balance.toLocaleString('ko-KR')}
                <span className="flex h-6 w-6 items-center justify-center rounded-full bg-primary-container font-label-sm text-on-primary-container">
                  P
                </span>
              </p>
            </div>
            <button
              type="button"
              disabled={points.balance < MIN_WITHDRAWAL_AMOUNT || points.balanceQuery.isPending}
              onClick={() => {
                setNotice(null)
                setWithdrawalOpen(true)
              }}
              className="min-h-11 rounded-xl bg-tertiary px-5 font-label-lg text-on-tertiary transition-opacity disabled:cursor-not-allowed disabled:opacity-40"
            >
              출금 신청
            </button>
          </div>

          <dl className="mt-lg flex flex-col gap-3 rounded-xl bg-tertiary-container/50 p-md">
            <PointSummary label="보유 포인트" value={formatPoints(points.balance)} strong />
            <PointSummary label="현재 예상 고용보험료" value="-0P" />
            <PointSummary label="현재 예상 산재보험료" value="-0P" />
          </dl>
          <p className="mt-2 font-label-sm text-secondary">보험료는 연동 API 준비 전까지 0P로 표시됩니다.</p>
          {notice && (
            <p
              role="status"
              className="mt-md rounded-xl bg-tertiary-container px-4 py-3 font-body-md text-on-tertiary-container"
            >
              {notice}
            </p>
          )}
        </section>

        <section
          aria-label="포인트 계좌 안내"
          className="border-t-8 border-surface-container-low bg-surface-container-lowest"
        >
          <div role="tablist" className="grid grid-cols-3 border-b border-surface-container px-container-margin">
            {infoTabs.map((tab) => (
              <button
                key={tab.value}
                type="button"
                role="tab"
                aria-selected={activeTab === tab.value}
                onClick={() => setActiveTab(tab.value)}
                className={`relative min-h-12 font-label-lg ${activeTab === tab.value ? 'text-on-surface' : 'text-secondary'}`}
              >
                {tab.label}
                {activeTab === tab.value && (
                  <span className="absolute inset-x-3 bottom-0 h-0.5 rounded-full bg-primary" />
                )}
              </button>
            ))}
          </div>
          <InfoTabContent tab={activeTab} />
        </section>

        <section
          aria-label="포인트 거래 내역"
          className="border-t-8 border-surface-container-low bg-surface-container-lowest"
        >
          <div className="flex items-center justify-center gap-lg px-container-margin py-md">
            <MonthButton
              label="이전 달"
              icon="chevron_left"
              onClick={() => setSelectedMonth((current) => shiftPointMonth(current, -1))}
            />
            <time
              className="min-w-28 text-center font-headline-sm text-on-surface"
              dateTime={selectedMonth.toISOString()}
            >
              {formatPointMonth(selectedMonth)}
            </time>
            <MonthButton
              label="다음 달"
              icon="chevron_right"
              onClick={() => setSelectedMonth((current) => shiftPointMonth(current, 1))}
            />
          </div>
          <div className="flex items-center justify-between border-b border-surface-container px-container-margin pb-md">
            <p className="font-body-md text-secondary">
              총 {points.transactions.length}건 · {monthlyTotal > 0 ? '+' : ''}
              {formatPoints(monthlyTotal)}
            </p>
            <label className="relative">
              <span className="sr-only">거래 유형</span>
              <select
                value={filter}
                onChange={(event) => setFilter(event.target.value as PointFilter)}
                className="min-h-10 appearance-none rounded-lg border border-surface-container bg-surface-container-lowest py-2 pl-3 pr-9 font-body-md text-on-surface focus:outline-none focus:ring-2 focus:ring-primary-container"
              >
                {pointFilterOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
              <span
                className="material-symbols-outlined pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-lg text-secondary"
                aria-hidden="true"
              >
                expand_more
              </span>
            </label>
          </div>

          {isLoading ? (
            <PointFeedback icon="progress_activity" message="포인트 내역을 불러오고 있어요." spin />
          ) : error ? (
            <PointFeedback
              icon="cloud_off"
              message={getPointErrorMessage(error)}
              onRetry={() => {
                void points.balanceQuery.refetch()
                void points.transactionsQuery.refetch()
              }}
            />
          ) : points.transactions.length === 0 ? (
            <PointFeedback icon="info" message="해당 내역이 없습니다." />
          ) : (
            <ul
              className={`divide-y divide-surface-container px-container-margin ${points.transactionsQuery.isFetching ? 'opacity-60' : ''}`}
            >
              {points.transactions.map((item, index) => {
                const signedAmount = getSignedPointAmount(item)
                return (
                  <li
                    key={item.transactionId ?? `${item.createdAt}-${index}`}
                    className="flex items-center justify-between gap-md py-md"
                  >
                    <div className="min-w-0">
                      <p className="font-label-lg text-on-surface">{getPointTransactionLabel(item.transactionType)}</p>
                      <time className="mt-1 block font-body-md text-secondary">
                        {formatPointTransactionDate(item.createdAt)}
                      </time>
                    </div>
                    <div className="shrink-0 text-right">
                      <p className={`font-label-lg ${signedAmount > 0 ? 'text-tertiary' : 'text-on-surface'}`}>
                        {signedAmount > 0 ? '+' : ''}
                        {formatPoints(signedAmount)}
                      </p>
                      <p className="mt-1 font-label-sm text-secondary">잔액 {formatPoints(item.balanceAfter)}</p>
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
        </section>
      </div>

      {withdrawalOpen && (
        <WithdrawalDialog
          balance={points.balance}
          pending={points.withdrawalMutation.isPending}
          error={points.withdrawalMutation.error ? getPointErrorMessage(points.withdrawalMutation.error) : null}
          onClose={() => setWithdrawalOpen(false)}
          onSubmit={async (values) => {
            try {
              await points.requestWithdrawal(values)
              setWithdrawalOpen(false)
              setNotice(`${formatPoints(values.amount)} 출금을 신청했습니다.`)
            } catch {
              // Mutation state renders the server error inside the dialog.
            }
          }}
        />
      )}
    </main>
  )
}

function PointSummary({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <dt className={strong ? 'font-body-md text-on-surface' : 'font-body-md text-secondary'}>{label}</dt>
      <dd className={strong ? 'font-label-lg text-on-surface' : 'font-body-md text-secondary'}>{value}</dd>
    </div>
  )
}

function InfoTabContent({ tab }: { tab: PointInfoTab }) {
  const content = {
    'charge-account': ['충전계좌', '라이더 포인트 충전계좌는 준비 중입니다.'],
    'withdrawal-account': ['출금계좌', '출금 신청할 때 은행·계좌번호·예금주명을 함께 입력합니다.'],
    guide: ['포인트 이용 가이드', '배송 완료 후 정산 포인트가 적립되며, 출금 신청 시 보유 포인트에서 차감됩니다.'],
  }[tab]
  return (
    <div role="tabpanel" className="px-container-margin py-md">
      <p className="font-label-lg text-on-surface">{content[0]}</p>
      <p className="mt-1 font-body-md text-secondary">{content[1]}</p>
    </div>
  )
}

function MonthButton({ label, icon, onClick }: { label: string; icon: string; onClick: () => void }) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className="flex h-9 w-9 items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container"
    >
      <span className="material-symbols-outlined" aria-hidden="true">
        {icon}
      </span>
    </button>
  )
}

function PointFeedback({
  icon,
  message,
  spin = false,
  onRetry,
}: {
  icon: string
  message: string
  spin?: boolean
  onRetry?: () => void
}) {
  return (
    <div className="flex min-h-64 flex-col items-center justify-center gap-3 bg-surface-container-low p-6 text-center">
      <span
        className={`material-symbols-outlined text-4xl text-secondary ${spin ? 'animate-spin' : ''}`}
        aria-hidden="true"
      >
        {icon}
      </span>
      <p role={onRetry ? 'alert' : 'status'} className="font-body-md text-secondary">
        {message}
      </p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="rounded-xl bg-primary-container px-5 py-2.5 font-label-lg text-on-primary-container"
        >
          다시 시도
        </button>
      )}
    </div>
  )
}

function WithdrawalDialog({
  balance,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  balance: number
  pending: boolean
  error: string | null
  onClose: () => void
  onSubmit: (values: WithdrawalFormValues) => Promise<void>
}) {
  const [amount, setAmount] = useState('')
  const [bankCode, setBankCode] = useState('')
  const [accountNumber, setAccountNumber] = useState('')
  const [accountHolderName, setAccountHolderName] = useState('')
  const numericAmount = Number(amount)
  const validation = getWithdrawalValidation({ amount, bankCode, accountNumber, accountHolderName }, balance)
  const hasStarted = Boolean(amount || bankCode || accountNumber || accountHolderName)

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 px-container-margin"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !pending) onClose()
      }}
    >
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="withdrawal-title"
        onSubmit={(event) => {
          event.preventDefault()
          if (validation || pending) return
          void onSubmit({
            amount: numericAmount,
            bankCode: bankCode.trim(),
            accountNumber,
            accountHolderName: accountHolderName.trim(),
          })
        }}
        className="max-h-[calc(100vh-2rem)] w-full max-w-sm overflow-y-auto rounded-2xl bg-surface-container-lowest p-lg shadow-xl"
      >
        <h2 id="withdrawal-title" className="font-headline-md text-on-surface">
          포인트 출금 신청
        </h2>
        <p className="mt-1 font-body-md text-secondary">출금 가능 {formatPoints(balance)}</p>
        <label className="mt-lg block font-label-lg text-on-surface" htmlFor="withdrawal-amount">
          출금 포인트
        </label>
        <div className="relative mt-2">
          <input
            id="withdrawal-amount"
            type="number"
            min={MIN_WITHDRAWAL_AMOUNT}
            max={balance}
            inputMode="numeric"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            autoFocus
            className="h-12 w-full rounded-xl border border-surface-container bg-surface-container-lowest px-4 pr-10 font-body-lg focus:outline-none focus:ring-2 focus:ring-primary-container"
          />
          <span className="absolute right-4 top-1/2 -translate-y-1/2 font-label-lg text-secondary">P</span>
        </div>
        <label className="mt-md block font-label-lg text-on-surface" htmlFor="withdrawal-bank">
          은행
        </label>
        <select
          id="withdrawal-bank"
          value={bankCode}
          onChange={(event) => setBankCode(event.target.value)}
          className="mt-2 h-12 w-full rounded-xl border border-surface-container bg-surface-container-lowest px-4 font-body-lg focus:outline-none focus:ring-2 focus:ring-primary-container"
        >
          <option value="">은행을 선택해 주세요</option>
          {withdrawalBankOptions.map((bank) => (
            <option key={bank.code} value={bank.code}>
              {bank.name}
            </option>
          ))}
        </select>
        <label className="mt-md block font-label-lg text-on-surface" htmlFor="withdrawal-account-number">
          계좌번호
        </label>
        <input
          id="withdrawal-account-number"
          type="text"
          inputMode="numeric"
          maxLength={20}
          value={accountNumber}
          onChange={(event) => setAccountNumber(event.target.value.replace(/\D/g, '').slice(0, 20))}
          placeholder="숫자만 입력"
          autoComplete="off"
          className="mt-2 h-12 w-full rounded-xl border border-surface-container bg-surface-container-lowest px-4 font-body-lg focus:outline-none focus:ring-2 focus:ring-primary-container"
        />
        <label className="mt-md block font-label-lg text-on-surface" htmlFor="withdrawal-account-holder">
          예금주명
        </label>
        <input
          id="withdrawal-account-holder"
          type="text"
          maxLength={50}
          value={accountHolderName}
          onChange={(event) => setAccountHolderName(event.target.value)}
          placeholder="예: 홍길동"
          autoComplete="name"
          className="mt-2 h-12 w-full rounded-xl border border-surface-container bg-surface-container-lowest px-4 font-body-lg focus:outline-none focus:ring-2 focus:ring-primary-container"
        />
        <p className="mt-2 font-label-sm text-secondary">계좌번호 원본은 저장하지 않고 마스킹된 정보만 남습니다.</p>
        {hasStarted && validation && (
          <p role="alert" className="mt-2 font-body-md text-error">
            {validation}
          </p>
        )}
        {error && (
          <p role="alert" className="mt-2 font-body-md text-error">
            {error}
          </p>
        )}
        <div className="mt-lg grid grid-cols-2 gap-gutter">
          <button
            type="button"
            disabled={pending}
            onClick={onClose}
            className="h-12 rounded-xl border border-surface-container font-label-lg text-on-surface disabled:opacity-50"
          >
            취소
          </button>
          <button
            type="submit"
            disabled={Boolean(validation) || pending}
            className="h-12 rounded-xl bg-primary-container font-label-lg text-on-primary-container disabled:opacity-40"
          >
            {pending ? '신청 중…' : '신청하기'}
          </button>
        </div>
      </form>
    </div>
  )
}
