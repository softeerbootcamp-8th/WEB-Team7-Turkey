import {
  useGetRiderPointBalance,
  useGetRiderPointTransactions,
  useRequestRiderWithdrawal,
} from '@/api/generated/rider-point/rider-point'
import type { GetRiderPointTransactionsType } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { isSamePointMonth, type PointFilter, type WithdrawalFormValues } from './-riderPoints'

const TRANSACTION_FETCH_SIZE = 100

export function useRiderPoints(selectedMonth: Date, filter: PointFilter) {
  const balanceQuery = useGetRiderPointBalance({ query: { retry: false } })
  const type = filter === 'ALL' ? undefined : (filter as GetRiderPointTransactionsType)
  const transactionsQuery = useGetRiderPointTransactions(
    { type, page: 0, size: TRANSACTION_FETCH_SIZE },
    { query: { retry: false, placeholderData: (previous) => previous } },
  )
  const withdrawalMutation = useRequestRiderWithdrawal()
  const transactions = (transactionsQuery.data?.data?.items ?? []).filter((item) =>
    isSamePointMonth(item.createdAt, selectedMonth),
  )
  const balance = balanceQuery.data?.data?.balance ?? transactionsQuery.data?.data?.balance ?? 0

  async function requestWithdrawal(values: WithdrawalFormValues) {
    await withdrawalMutation.mutateAsync({
      data: { ...values, requestKey: crypto.randomUUID() },
    })
    await Promise.all([balanceQuery.refetch(), transactionsQuery.refetch()])
  }

  return {
    balance,
    balanceQuery,
    transactions,
    transactionsQuery,
    withdrawalMutation,
    requestWithdrawal,
  }
}
