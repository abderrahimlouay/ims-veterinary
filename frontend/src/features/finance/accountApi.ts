import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PaymentMethod } from '../sales/saleApi'

/**
 * api.md §4.9, §4.12, §5.9: a customer's account (ADR-118, ADR-134, ADR-135). Balances, open amounts and allocations
 * are the server's; the screen shows them and never computes them (BR-SALE-008).
 */
export interface OpenInvoice {
  id: number
  number: string
  issuedAt: string
  dueDate: string | null
  totalTtc: string
  open: string
}

export interface Advance {
  paymentId: number
  paidAt: string
  unallocated: string
}

export interface OpenItems {
  invoices: OpenInvoice[]
  advances: Advance[]
}

export interface Allocation {
  id: number
  invoiceId: number
  invoiceNumber: string | null
  amount: string
  createdAt: string
}

export interface PaymentDetail {
  id: number
  customer: { id: number; name: string } | null
  method: PaymentMethod
  amount: string
  reference: string | null
  cashSessionId: number | null
  paidAt: string
  reverses: number | null
  reversedBy: number | null
  allocations: Allocation[]
  unallocated: string
  customerBalance: string | null
}

export type StatementKind = 'invoice' | 'credit_note' | 'payment' | 'reversal'

export interface StatementLine {
  date: string
  at: string
  kind: StatementKind
  documentId: number | null
  paymentId: number | null
  number: string | null
  method: PaymentMethod | null
  reference: string | null
  debit: string
  credit: string
  balance: string
}

export interface Statement {
  customer: { id: number; name: string }
  from: string | null
  to: string
  openingBalance: string
  lines: StatementLine[]
  totalDebit: string
  totalCredit: string
  closingBalance: string
  uninvoicedDeliveryNotes: string
}

export interface AllocationWrite {
  invoiceId: number
  amount: string
}

/** `allocations` absent: the server settles the oldest due first; `[]`: all of it an advance (ADR-134). */
export interface AccountPaymentWrite {
  customerId: number
  method: PaymentMethod
  amount: string
  reference: string | null
  allocations?: AllocationWrite[]
}

const keys = {
  open: (customerId: number) => ['customers', customerId, 'open-invoices'] as const,
  statement: (customerId: number, from: string, to: string) => ['customers', customerId, 'statement', from, to] as const,
}

export function useOpenItems(customerId: number, enabled = true) {
  return useQuery({
    enabled,
    queryKey: keys.open(customerId),
    queryFn: () => apiFetch<OpenItems>(`/api/customers/${customerId}/open-invoices`),
    staleTime: 0,
  })
}

/** `from`/`to`: business dates, empty for "from the first entry" / "today". */
function statementParams(from: string, to: string): URLSearchParams {
  const params = new URLSearchParams()
  if (from !== '') {
    params.set('from', from)
  }
  if (to !== '') {
    params.set('to', to)
  }
  return params
}

/** The statement of the same period as a PDF (ADR-137, ADR-143). */
export function statementPdfUrl(customerId: number, from: string, to: string): string {
  const params = statementParams(from, to)
  params.set('format', 'pdf')
  return `/api/customers/${customerId}/statement?${params}`
}

export function useStatement(customerId: number, from: string, to: string, enabled = true) {
  const params = statementParams(from, to)
  return useQuery({
    enabled,
    queryKey: keys.statement(customerId, from, to),
    queryFn: () => apiFetch<Statement>(`/api/customers/${customerId}/statement?${params}`),
    staleTime: 0,
  })
}

/** Everything a payment changes: the open items, the statement and the balance of that customer. */
function useAccountInvalidation(customerId: number) {
  const client = useQueryClient()
  return () => client.invalidateQueries({ queryKey: ['customers', customerId] })
}

/** `key`: one per dialog, so a retry never records the payment twice (ADR-104). */
export function useRecordPayment(customerId: number) {
  const invalidate = useAccountInvalidation(customerId)
  return useMutation({
    mutationFn: ({ payment, key }: { payment: AccountPaymentWrite; key: string }) =>
      apiFetch<PaymentDetail>('/api/payments/customers', { method: 'POST', json: payment, headers: { 'Idempotency-Key': key } }),
    onSuccess: invalidate,
  })
}

export function useApplyAdvance(customerId: number) {
  const invalidate = useAccountInvalidation(customerId)
  return useMutation({
    mutationFn: ({ paymentId, allocations, key }: { paymentId: number; allocations: AllocationWrite[]; key: string }) =>
      apiFetch<PaymentDetail>(`/api/payments/customers/${paymentId}/allocations`, {
        method: 'POST',
        json: { allocations },
        headers: { 'Idempotency-Key': key },
      }),
    onSuccess: invalidate,
  })
}

export function useReversePayment(customerId: number) {
  const invalidate = useAccountInvalidation(customerId)
  return useMutation({
    mutationFn: ({ paymentId, reason, key }: { paymentId: number; reason: string; key: string }) =>
      apiFetch<PaymentDetail>(`/api/payments/customers/${paymentId}/reverse`, {
        method: 'POST',
        json: { reason },
        headers: { 'Idempotency-Key': key },
      }),
    onSuccess: invalidate,
  })
}
