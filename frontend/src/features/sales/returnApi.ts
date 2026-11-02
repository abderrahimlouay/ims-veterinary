import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'
import type { PaymentWrite } from './saleApi'

/**
 * api.md §4.11 and §5.8: customer returns (W3, ADR-127…131). Eligibility, quantities and every amount come from the
 * server (BR-SALE-008, ADR-129, ADR-130): the screen shows them and never computes them.
 */
export type Disposition = 'restock' | 'discard'
export type DiscardReason = 'opened' | 'packaging_damaged' | 'expired' | 'recalled' | 'cold_chain_doubt' | 'other'
export const DISCARD_REASONS: readonly DiscardReason[] = ['opened', 'packaging_damaged', 'expired', 'recalled', 'cold_chain_doubt', 'other']
export type RestockRefusal = 'not_sealed' | 'lot_expired' | 'product_restricted'

export interface ReturnProduct {
  id: number
  sku: string
  commercialName: string
  baseUnit: string
}

/** `status`: the lot's stored status; a blocked or recalled lot stays unsellable after a restock. */
export interface ReturnLot {
  id: number
  lotNumber: string
  expiryDate: string
  status: 'active' | 'blocked' | 'recalled'
}

export interface PreviewLine {
  saleLineId: number
  product: ReturnProduct
  lot: ReturnLot
  soldBase: string
  returnedBase: string
  returnableBase: string
  restockAllowed: boolean
  restockRefusal: RestockRefusal | null
}

export interface ReturnPreview {
  document: { id: number; number: string; docType: string; creditSale: boolean; customer: { id: number; name: string } | null; issuedAt: string }
  lines: PreviewLine[]
  creditNote: { totalHt: string; totalVat: string; totalTtc: string; lines: { saleLineId: number; quantityBase: string; lineHt: string; lineVat: string; lineTtc: string }[] }
  refundRequired: boolean
}

export interface ReturnLineWrite {
  saleLineId: number
  quantity: string
  unit: 'base'
  sealed: boolean
  disposition: Disposition
  discardReason?: DiscardReason
  discardNote?: string
}

export interface ReturnWrite {
  documentId: number
  reason: string
  lines: ReturnLineWrite[]
  refund: PaymentWrite[]
}

export interface CreditNoteRef {
  id: number
  number: string
  totalHt: string
  totalVat: string
  totalTtc: string
}

export interface CustomerReturnSummary {
  id: number
  number: string
  document: { id: number; number: string; docType: string }
  customer: { id: number; name: string } | null
  creditNote: CreditNoteRef
  refunded: boolean
  reason: string
  createdAt: string
  createdBy: number
}

export interface CustomerReturnDetail {
  id: number
  number: string
  document: { id: number; number: string; docType: string }
  customer: { id: number; name: string } | null
  reason: string
  createdAt: string
  createdBy: number
  creditNote: CreditNoteRef
  lines: {
    id: number
    saleLineId: number
    product: ReturnProduct
    lot: ReturnLot
    quantityBase: string
    sealed: boolean
    disposition: Disposition
    discardReason: DiscardReason | null
    discardNote: string | null
    movementIds: number[]
  }[]
  refunds: { id: number; method: PaymentWrite['method']; amount: string; reference: string | null; cashSessionId: number | null }[]
}

export interface ReturnFilters {
  customerId: string
  documentId: string
  page: number
}

const returnKeys = {
  all: ['customer-returns'] as const,
  list: (f: ReturnFilters) => ['customer-returns', 'list', f] as const,
  detail: (id: number) => ['customer-returns', id] as const,
}

export function useCustomerReturns(filters: ReturnFilters) {
  return useQuery({
    queryKey: returnKeys.list(filters),
    queryFn: () => {
      const params = new URLSearchParams({ page: String(filters.page), size: '20' })
      if (filters.customerId !== '') {
        params.set('customerId', filters.customerId)
      }
      if (filters.documentId !== '') {
        params.set('documentId', filters.documentId)
      }
      return apiFetch<{ content: CustomerReturnSummary[]; page: PageMetadata }>(`/api/returns/customer?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

export function useCustomerReturn(id: number) {
  return useQuery({ queryKey: returnKeys.detail(id), queryFn: () => apiFetch<CustomerReturnDetail>(`/api/returns/customer/${id}`) })
}

/** Nothing is written; `lines` empty lists every line of the document with what is left to return. */
export function previewReturn(documentId: number, lines: ReturnLineWrite[]) {
  return apiFetch<ReturnPreview>('/api/returns/customer/preview', { method: 'POST', json: { documentId, lines } })
}

/** `key`: one per return being entered, so a retry never returns or refunds twice (ADR-104). */
export function useCreateCustomerReturn() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ body, key }: { body: ReturnWrite; key: string }) =>
      apiFetch<CustomerReturnDetail>('/api/returns/customer', { method: 'POST', json: body, headers: { 'Idempotency-Key': key } }),
    onSuccess: () => queryClient.invalidateQueries(),
  })
}
