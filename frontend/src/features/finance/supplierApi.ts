import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'
import type { PaymentMethod } from '../sales/saleApi'
import type { AllocationWrite } from './accountApi'

/**
 * api.md §4.6, §4.12: supplier invoices and credit notes (ADR-133), the payables of a supplier and supplier payments
 * (ADR-132, ADR-135). Open amounts, balances and allocations are the server's; the screens never compute them
 * (BR-SALE-008).
 */
export type SupplierInvoiceStatus = 'recorded' | 'cancelled'

export interface SupplierInvoiceSummary {
  id: number
  supplierId: number
  supplierName: string
  invoiceNumber: string
  creditNote: boolean
  invoiceDate: string
  dueDate: string | null
  totalTtc: string
  open: string
  status: SupplierInvoiceStatus
}

export interface InvoiceWarning {
  code: 'RECEIPT_TOTAL_DIFFERS'
  context: { receiptId?: number; receiptValue?: string }
}

export interface SupplierInvoiceDetail {
  id: number
  supplier: { id: number; name: string }
  invoiceNumber: string
  creditNote: boolean
  receipt: { id: number; number: string } | null
  supplierReturn: { id: number; number: string } | null
  invoiceDate: string
  dueDate: string | null
  totalTtc: string
  open: string
  status: SupplierInvoiceStatus
  applications: { id: number; otherId: number; otherNumber: string; amount: string; createdAt: string }[]
  payments: { id: number; paymentId: number; amount: string; createdAt: string }[]
  cancelReason: string | null
  cancelledBy: number | null
  cancelledAt: string | null
  createdBy: number
  createdAt: string
  warnings: InvoiceWarning[]
}

export interface SupplierInvoiceWrite {
  supplierId: number | null
  invoiceNumber: string
  receiptId: number | null
  supplierReturnId: number | null
  invoiceDate: string
  dueDate: string | null
  totalTtc: string
  creditNote: boolean
}

export interface InvoiceFilters {
  supplierId: string
  creditNote: string
  open: boolean
  dueBefore: string
}

export interface PayableInvoice {
  id: number
  invoiceNumber: string
  invoiceDate: string
  dueDate: string | null
  totalTtc: string
  open: string
}

export interface Payables {
  supplier: { id: number; name: string }
  balance: string
  invoices: PayableInvoice[]
  creditNotes: { id: number; invoiceNumber: string; totalTtc: string; unapplied: string }[]
  advances: { paymentId: number; paidAt: string; unallocated: string }[]
}

export interface SupplierPaymentDetail {
  id: number
  supplier: { id: number; name: string }
  method: PaymentMethod
  amount: string
  reference: string | null
  cashSessionId: number | null
  paidAt: string
  reverses: number | null
  reversedBy: number | null
  reversalReason: string | null
  allocations: { id: number; invoiceId: number; invoiceNumber: string | null; amount: string; createdAt: string }[]
  unallocated: string
  supplierBalance: string
}

export interface SupplierPaymentSummary {
  id: number
  supplierId: number
  method: PaymentMethod
  amount: string
  reference: string | null
  paidAt: string
  reverses: number | null
  reversed: boolean
}

/** `allocations` absent: the server settles the oldest due first; `[]`: all of it an advance (ADR-132). */
export interface SupplierPaymentWrite {
  supplierId: number
  method: PaymentMethod
  amount: string
  reference: string | null
  allocations?: AllocationWrite[]
}

/** Every key starts with one of these, so a write refreshes the lists, the details and the payables at once. */
const keys = {
  invoices: ['supplier-invoices'] as const,
  invoiceList: (filters: InvoiceFilters, page: number) => ['supplier-invoices', 'list', filters, page] as const,
  invoice: (id: number) => ['supplier-invoices', id] as const,
  payables: (supplierId: number) => ['supplier-payables', supplierId] as const,
  payments: ['supplier-payments'] as const,
  paymentList: (supplierId: number, page: number) => ['supplier-payments', 'list', supplierId, page] as const,
}

function useFinanceInvalidation() {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: keys.invoices }),
      client.invalidateQueries({ queryKey: ['supplier-payables'] }),
      client.invalidateQueries({ queryKey: keys.payments }),
      client.invalidateQueries({ queryKey: ['cash-sessions'] }),
    ])
}

export function useSupplierInvoices(filters: InvoiceFilters, page: number) {
  return useQuery({
    queryKey: keys.invoiceList(filters, page),
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20' })
      if (filters.supplierId !== '') {
        params.set('supplierId', filters.supplierId)
      }
      if (filters.creditNote !== '') {
        params.set('creditNote', filters.creditNote)
      }
      if (filters.open) {
        params.set('open', 'true')
      }
      if (filters.dueBefore !== '') {
        params.set('dueBefore', filters.dueBefore)
      }
      return apiFetch<{ content: SupplierInvoiceSummary[]; page: PageMetadata }>(`/api/supplier-invoices?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

export function useSupplierInvoice(id: number) {
  return useQuery({ queryKey: keys.invoice(id), queryFn: () => apiFetch<SupplierInvoiceDetail>(`/api/supplier-invoices/${id}`) })
}

export function useRecordSupplierInvoice() {
  const invalidate = useFinanceInvalidation()
  return useMutation({
    mutationFn: (invoice: SupplierInvoiceWrite) => apiFetch<SupplierInvoiceDetail>('/api/supplier-invoices', { method: 'POST', json: invoice }),
    onSuccess: invalidate,
  })
}

export function useCancelSupplierInvoice(id: number) {
  const invalidate = useFinanceInvalidation()
  return useMutation({
    mutationFn: (reason: string) => apiFetch<SupplierInvoiceDetail>(`/api/supplier-invoices/${id}/cancel`, { method: 'POST', json: { reason } }),
    onSuccess: invalidate,
  })
}

/** `key`: one per dialog, so a retry never applies the credit note twice (ADR-104). */
export function useApplyCreditNote() {
  const invalidate = useFinanceInvalidation()
  return useMutation({
    mutationFn: ({ creditNoteId, allocations, key }: { creditNoteId: number; allocations: AllocationWrite[]; key: string }) =>
      apiFetch<SupplierInvoiceDetail>(`/api/supplier-invoices/${creditNoteId}/applications`, {
        method: 'POST',
        json: { allocations },
        headers: { 'Idempotency-Key': key },
      }),
    onSuccess: invalidate,
  })
}

export function usePayables(supplierId: number, enabled = true) {
  return useQuery({
    enabled,
    queryKey: keys.payables(supplierId),
    queryFn: () => apiFetch<Payables>(`/api/suppliers/${supplierId}/payables`),
    staleTime: 0,
  })
}

export function useSupplierPayments(supplierId: number, page: number, enabled = true) {
  return useQuery({
    enabled,
    queryKey: keys.paymentList(supplierId, page),
    queryFn: () =>
      apiFetch<{ content: SupplierPaymentSummary[]; page: PageMetadata }>(`/api/payments/suppliers?supplierId=${supplierId}&page=${page}&size=20`),
    placeholderData: keepPreviousData,
  })
}

export function useRecordSupplierPayment() {
  const invalidate = useFinanceInvalidation()
  return useMutation({
    mutationFn: ({ payment, key }: { payment: SupplierPaymentWrite; key: string }) =>
      apiFetch<SupplierPaymentDetail>('/api/payments/suppliers', { method: 'POST', json: payment, headers: { 'Idempotency-Key': key } }),
    onSuccess: invalidate,
  })
}

export function useApplySupplierAdvance() {
  const invalidate = useFinanceInvalidation()
  return useMutation({
    mutationFn: ({ paymentId, allocations, key }: { paymentId: number; allocations: AllocationWrite[]; key: string }) =>
      apiFetch<SupplierPaymentDetail>(`/api/payments/suppliers/${paymentId}/allocations`, {
        method: 'POST',
        json: { allocations },
        headers: { 'Idempotency-Key': key },
      }),
    onSuccess: invalidate,
  })
}

export function useReverseSupplierPayment() {
  const invalidate = useFinanceInvalidation()
  return useMutation({
    mutationFn: ({ paymentId, reason, key }: { paymentId: number; reason: string; key: string }) =>
      apiFetch<SupplierPaymentDetail>(`/api/payments/suppliers/${paymentId}/reverse`, {
        method: 'POST',
        json: { reason },
        headers: { 'Idempotency-Key': key },
      }),
    onSuccess: invalidate,
  })
}
