import {
  useGetRiderPointBalance,
  useGetRiderPointTransactions,
  useProcessRiderWithdrawal,
  useRequestRiderWithdrawal,
} from '@/api/generated/rider-point/rider-point'
import type { GetRiderPointTransactionsType } from '@/api/generated/turkeyQuickDeliveryAPI.schemas'
import { isSamePointMonth, type PointFilter } from './-riderPoints'

const TRANSACTION_FETCH_SIZE = 100

/**
 * MockPayoutGateway 가 해석하는 토큰(backend `MockPayoutGateway.DECLINE_TOKEN`). 실 은행 API 를
 * 붙이면 이 화면의 "모의 승인/거절" 버튼 자체가 사라지므로, 값이 백엔드와 어긋나도 컴파일로는
 * 잡히지 않는다 — 값을 바꾸면 두 쪽을 함께 바꿔야 한다.
 */
const MOCK_PAYOUT_DECLINE_TOKEN = 'mock_decline'
const MOCK_PAYOUT_APPROVE_TOKEN = 'mock_approve'

export function useRiderPoints(selectedMonth: Date, filter: PointFilter) {
  const balanceQuery = useGetRiderPointBalance({ query: { retry: false } })
  const type = filter === 'ALL' ? undefined : (filter as GetRiderPointTransactionsType)
  const transactionsQuery = useGetRiderPointTransactions(
    { type, page: 0, size: TRANSACTION_FETCH_SIZE },
    { query: { retry: false, placeholderData: (previous) => previous } },
  )
  const withdrawalMutation = useRequestRiderWithdrawal()
  const processMutation = useProcessRiderWithdrawal()
  const transactions = (transactionsQuery.data?.data?.items ?? []).filter((item) =>
    isSamePointMonth(item.createdAt, selectedMonth),
  )
  const balance = balanceQuery.data?.data?.balance ?? transactionsQuery.data?.data?.balance ?? 0

  async function requestWithdrawal(amount: number) {
    await withdrawalMutation.mutateAsync({
      data: { amount, requestKey: crypto.randomUUID() },
    })
    await Promise.all([balanceQuery.refetch(), transactionsQuery.refetch()])
  }

  /**
   * 실제 은행 API 가 없어 라이더 화면에서 "모의 승인/거절" 버튼으로 직접 결과를 트리거한다
   * (사람 확인, #90 후속). PayoutGateway 를 실 은행 API 로 교체하면 이 버튼은 제거하고, 출금은
   * 서버가 능동적으로(자동 처리 또는 실제 웹훅으로) 확정하는 흐름으로 바뀌어야 한다.
   */
  async function processWithdrawal(withdrawalId: number, approve: boolean) {
    await processMutation.mutateAsync({
      withdrawalId,
      data: { authToken: approve ? MOCK_PAYOUT_APPROVE_TOKEN : MOCK_PAYOUT_DECLINE_TOKEN },
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
    processMutation,
    processWithdrawal,
  }
}
