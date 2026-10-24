import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'

/** api.md §4.7 purchase orders (BR-PURCH-001…003, ADR-093…097). Amounts are always the server's. */
export type OrderStatus = 'draft' | 'validated' | 'sent' | 'partially_received' | 'closed' | 'cancelled'
export const ORDER_STATUSES: readonly OrderStatus[] = ['draft', 'validated', 'sent', 'partially_received', 'closed', 'cancelled']
export type OrderAction = 'validate' | 'send' | 'cancel' | 'close'

/** Actions offered per status (BR-PURCH-001, ADR-096); cancel and close ask for a reason. */
export const ACTIONS_BY_STATUS: Record<OrderStatus, readonly OrderAction[]> = {
  draft: ['validate', 'cancel'],
  validated: ['send', 'cancel'],
  sent: ['cancel'],
  partially_received: ['close'],
  closed: [],
  cancelled: [],
}

export interface OrderLine {
  id: number
  product: { id: number; sku: string; commercialName: string; baseUnit: string }
  unit: { id: number; name: string; factorToBase: string } | null
  quantity: string
  quantityBase: string
  receivedBase: string
  outstandingBase: string
  unitPrice: string
  discountPct: string
  vatRate: string
  lineHt: string
  lineVat: string
}

export interface PurchaseOrderSummary {
  id: number
  number: string
  supplier: { id: number; name: string; active: boolean }
  status: OrderStatus
  orderDate: string
  expectedDate: string | null
  totalTtc: string
}

export interface PurchaseOrderDetail extends PurchaseOrderSummary {
  lines: OrderLine[]
  totalHt: string
  totalVat: string
  createdBy: { id: number; fullName: string }
  createdAt: string
  validatedAt: string | null
  sentAt: string | null
  cancelReason: string | null
  cancelledBy: { id: number; fullName: string } | null
  cancelledAt: string | null
  closedReason: string | null
  closedBy: { id: number; fullName: string } | null
  closedAt: string | null
  receipts: { id: number; number: string; receivedAt: string; status: string }[]
  version: number
}

/** A requested line: no amounts, they are computed by the server (BR-PURCH-002). */
export interface OrderLineWrite {
  productId: number
  unitId: number | null
  quantity: string
  unitPrice: string
  discountPct: string | null
  vatRate: string | null
}

export interface OrderWrite {
  supplierId: number | null
  expectedDate: string | null
  lines: OrderLineWrite[]
}

export interface PricePrefill {
  unitPrice: string
  discountPct: string
  vatRate: string
  source: 'history' | 'product'
}

const orderKeys = {
  all: ['purchase-orders'] as const,
  list: (status: string, supplierId: string, page: number) => ['purchase-orders', 'list', status, supplierId, page] as const,
  detail: (id: number) => ['purchase-orders', id] as const,
}

export function useOrders(status: string, supplierId: string, page: number) {
  return useQuery({
    queryKey: orderKeys.list(status, supplierId, page),
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20' })
      if (status !== '') {
        params.set('status', status)
      }
      if (supplierId !== '') {
        params.set('supplierId', supplierId)
      }
      return apiFetch<{ content: PurchaseOrderSummary[]; page: PageMetadata }>(`/api/purchase-orders?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

export function useOrder(id: number) {
  return useQuery({ queryKey: orderKeys.detail(id), queryFn: () => apiFetch<PurchaseOrderDetail>(`/api/purchase-orders/${id}`) })
}

/** Creates (`id` undefined) or replaces a draft with its current version (ADR-027). */
export function useSaveOrder() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, order, version }: { id?: number; order: OrderWrite; version?: number }) =>
      apiFetch<PurchaseOrderDetail>(id === undefined ? '/api/purchase-orders' : `/api/purchase-orders/${id}`, {
        method: id === undefined ? 'POST' : 'PUT',
        json: id === undefined ? order : { ...order, version },
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: orderKeys.all }),
  })
}

export function useOrderAction(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ action, reason }: { action: OrderAction; reason?: string }) =>
      apiFetch<PurchaseOrderDetail>(`/api/purchase-orders/${id}/${action}`, {
        method: 'POST',
        json: reason === undefined ? undefined : { reason },
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: orderKeys.all }),
  })
}

/** The price suggested for a new line (ADR-099): per ordered unit, from the supplier's history or the product. */
export function fetchPrefill(supplierId: number, productId: number, unitId: number | null) {
  const params = new URLSearchParams({ supplierId: String(supplierId), productId: String(productId) })
  if (unitId !== null) {
    params.set('unitId', String(unitId))
  }
  return apiFetch<PricePrefill>(`/api/purchase-orders/prefill?${params}`)
}
