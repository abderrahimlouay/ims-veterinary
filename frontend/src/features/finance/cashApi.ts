import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'
import type { PaymentMethod } from '../sales/saleApi'

/** api.md §4.12: all cash sessions, a session's report and manual movements (ADR-136). Amounts are the server's. */
export interface CashSessionView {
  id: number
  cashierId: number
  status: 'open' | 'closed'
  openedAt: string
  closedAt: string | null
  openingAmount: string
  expectedAmount: string
  countedAmount: string | null
  varianceAmount: string | null
  version: number
}

export type CashSource = 'sales' | 'account_payments' | 'refunds' | 'supplier_payments' | 'reversals' | 'manual'

export interface CashReport {
  session: CashSessionView
  closedBy: number | null
  openingAmount: string
  bySource: { source: CashSource; in: string; out: string }[]
  nonCash: {
    kind: 'customer_payment' | 'customer_refund' | 'supplier_payment'
    id: number
    reversal: boolean
    method: PaymentMethod
    reference: string | null
    amount: string
    direction: 'in' | 'out'
    at: string
  }[]
  expected: string
  counted: string | null
  variance: string | null
}

export interface MovementWrite {
  direction: 'in' | 'out'
  amount: string
  label: string
}

const keys = {
  list: (page: number) => ['cash-sessions', 'list', page] as const,
  report: (id: number) => ['cash-sessions', id, 'report'] as const,
}

export function useCashSessions(page: number) {
  return useQuery({
    queryKey: keys.list(page),
    queryFn: () => apiFetch<{ content: CashSessionView[]; page: PageMetadata }>(`/api/cash-sessions?page=${page}&size=20`),
    placeholderData: keepPreviousData,
  })
}

export function useCashReport(id: number) {
  return useQuery({ queryKey: keys.report(id), queryFn: () => apiFetch<CashReport>(`/api/cash-sessions/${id}/report`), staleTime: 0 })
}

/** Every cash-session query (the user's own included) shows the new expected amount. */
function useSessionInvalidation() {
  const client = useQueryClient()
  return () => client.invalidateQueries({ queryKey: ['cash-sessions'] })
}

/** `key`: one per dialog, so a retry never posts the movement twice (ADR-104). */
export function useAddMovement(sessionId: number) {
  const invalidate = useSessionInvalidation()
  return useMutation({
    mutationFn: ({ movement, key }: { movement: MovementWrite; key: string }) =>
      apiFetch<CashSessionView>(`/api/cash-sessions/${sessionId}/movements`, {
        method: 'POST',
        json: movement,
        headers: { 'Idempotency-Key': key },
      }),
    onSuccess: invalidate,
  })
}

/** A manager closing someone else's session (`cash.manage`); the cashier closes theirs from the counter. */
export function useCloseAnySession(sessionId: number) {
  const invalidate = useSessionInvalidation()
  return useMutation({
    mutationFn: (countedAmount: string) =>
      apiFetch<CashSessionView>(`/api/cash-sessions/${sessionId}/close`, { method: 'POST', json: { countedAmount } }),
    onSuccess: invalidate,
  })
}
