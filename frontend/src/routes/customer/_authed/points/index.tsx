import { createFileRoute, Link, useRouter } from '@tanstack/react-router'
import { useGetCustomerPointBalance } from '@/api/generated/customer-point/customer-point'
import { formatCustomerPoints, getCustomerPointErrorMessage } from './-customerPoints'
import { PointHistoryTable } from './-components/PointHistoryTable'

export const Route = createFileRoute('/customer/_authed/points/')({ component: CustomerPoints })

function CustomerPoints() {
  const router = useRouter()
  const balanceQuery = useGetCustomerPointBalance({
    query: { retry: false, refetchOnMount: 'always' },
  })
  const balance = balanceQuery.data?.data?.balance
  const hasInvalidBalance = balanceQuery.isSuccess && balance == null

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-md flex-col bg-surface text-on-surface shadow-sm">
      <header className="sticky top-0 z-20 flex h-16 items-center border-b border-outline-variant bg-surface-container-lowest px-4">
        <button
          type="button"
          aria-label="고객 홈으로 돌아가기"
          onClick={() => void router.navigate({ to: '/customer' })}
          className="flex h-11 w-11 items-center justify-center rounded-full text-on-surface transition-colors hover:bg-surface-container-low"
        >
          <span aria-hidden="true" className="material-symbols-outlined">arrow_back</span>
        </button>
        <h1 className="flex-1 text-center text-headline-md font-bold">포인트</h1>
        <button
          type="button"
          aria-label="포인트 잔액 다시 불러오기"
          onClick={() => void balanceQuery.refetch()}
          disabled={balanceQuery.isFetching}
          className="flex h-11 w-11 items-center justify-center rounded-full text-on-surface transition-colors hover:bg-surface-container-low disabled:opacity-50"
        >
          <span
            aria-hidden="true"
            className={`material-symbols-outlined ${balanceQuery.isFetching ? 'animate-spin' : ''}`}
          >
            refresh
          </span>
        </button>
      </header>

      <main aria-label="고객 포인트" className="flex flex-1 flex-col bg-surface-container-low">
        <section className="bg-surface-container-lowest px-5 py-6">
          <div className="rounded-2xl bg-primary-container p-5 text-on-primary-container">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <p className="text-body-md">보유 포인트</p>
                {balanceQuery.isPending ? (
                  <p role="status" className="mt-2 text-body-lg">잔액을 불러오고 있어요…</p>
                ) : balanceQuery.isError || hasInvalidBalance ? (
                  <div className="mt-2">
                    <p role="alert" className="text-body-md font-bold text-error">
                      {balanceQuery.isError
                        ? getCustomerPointErrorMessage(
                            balanceQuery.error,
                            '포인트 잔액을 불러오지 못했습니다.',
                          )
                        : '포인트 잔액을 불러오지 못했습니다.'}
                    </p>
                    <button
                      type="button"
                      onClick={() => void balanceQuery.refetch()}
                      className="mt-3 rounded-lg bg-surface-container-lowest px-4 py-2 text-label-md font-bold text-on-surface"
                    >
                      다시 시도
                    </button>
                  </div>
                ) : (
                  <p className="mt-1 text-display-sm font-bold tracking-tight">
                    {formatCustomerPoints(balance)}
                  </p>
                )}
              </div>
              <span
                aria-hidden="true"
                className="material-symbols-outlined flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-surface-container-lowest/80 text-2xl text-primary"
              >
                account_balance_wallet
              </span>
            </div>
            <p className="mt-4 text-label-md opacity-75">표시된 잔액은 조회 시점 기준입니다.</p>
          </div>

          <Link
            to="/customer/points/charge"
            className="mt-4 flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-primary-container text-label-lg font-bold text-on-primary-container transition-transform active:scale-[0.98]"
          >
            <span aria-hidden="true" className="material-symbols-outlined">add_circle</span>
            포인트 충전하기
          </Link>
        </section>

        <div className="mt-2">
          <PointHistoryTable />
        </div>
      </main>
    </div>
  )
}
