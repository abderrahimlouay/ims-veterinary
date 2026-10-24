import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'

/** api.md §4.8 and §5.4 goods receipts (W1b, W1d). Lots, costs and base quantities are the server's. */
export type ReceiptStatus = 'confirmed' | 'cancelled'

export interface ReceiptLine {
  id: number
  poLineId: number | null
  product: { id: number; sku: string; commercialName: string; baseUnit: string }
  lotId: number
  lotNumber: string
  expiryDate: string
  quantityBase: string
  unitCost: string
  conforming: boolean
  lotBlocked: boolean
  movementId: number
}

export interface ReceiptSummary {
  id: number
  number: string
  supplier: { id: number; name: string; active: boolean }
  purchaseOrder: { id: number; number: string; status: string } | null
  receivedAt: string
  status: ReceiptStatus
}

export interface ReceiptDetail extends ReceiptSummary {
  supplierDocRef: string | null
  notes: string | null
  receivedBy: { id: number; fullName: string }
  lines: ReceiptLine[]
  cancelReason: string | null
  cancelledBy: { id: number; fullName: string } | null
  cancelledAt: string | null
}

export interface ReceivedLineWrite {
  poLineId: number | null
  productId: number
  unitId: number | null
  quantity: string
  lotNumber: string
  expiryDate: string
  unitCost: string
  conforming: boolean
}

export interface ReceiptWrite {
  supplierId: number | null
  purchaseOrderId: number | null
  supplierDocRef: string | null
  notes: string | null
  confirmLotBlocking: boolean
  lines: ReceivedLineWrite[]
}

const receiptKeys = {
  all: ['goods-receipts'] as const,
  list: (status: string, supplierId: string, page: number) => ['goods-receipts', 'list', status, supplierId, page] as const,
  detail: (id: number) => ['goods-receipts', id] as const,
}

export function useReceipts(status: string, supplierId: string, page: number) {
  return useQuery({
    queryKey: receiptKeys.list(status, supplierId, page),
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20' })
      if (status !== '') {
        params.set('status', status)
      }
      if (supplierId !== '') {
        params.set('supplierId', supplierId)
      }
      return apiFetch<{ content: ReceiptSummary[]; page: PageMetadata }>(`/api/goods-receipts?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

export function useReceipt(id: number) {
  return useQuery({ queryKey: receiptKeys.detail(id), queryFn: () => apiFetch<ReceiptDetail>(`/api/goods-receipts/${id}`) })
}

/**
 * Confirms a receipt with its `Idempotency-Key` (ADR-104): the caller keeps one key per receipt being entered, so a
 * retry after a lost answer returns the first receipt instead of receiving twice.
 */
export function useConfirmReceipt() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ receipt, key }: { receipt: ReceiptWrite; key: string }) =>
      apiFetch<ReceiptDetail>('/api/goods-receipts', { method: 'POST', json: receipt, headers: { 'Idempotency-Key': key } }),
    onSuccess: () => queryClient.invalidateQueries(),
  })
}

export function useCancelReceipt(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (reason: string) => apiFetch<ReceiptDetail>(`/api/goods-receipts/${id}/cancel`, { method: 'POST', json: { reason } }),
    onSuccess: () => queryClient.invalidateQueries(),
  })
}
