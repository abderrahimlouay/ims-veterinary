import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'

/** api.md §4.4. `status` is the effective status and `alertLevel` the level computed by the server (ADR-056/059). */
export type LotStatus = 'active' | 'blocked' | 'recalled' | 'expired' | 'depleted'
export type AlertLevel = 'expired' | 'critical' | 'warning' | 'notice'
export const LOT_STATUSES: readonly LotStatus[] = ['active', 'blocked', 'recalled', 'expired', 'depleted']
export const ALERT_LEVELS: readonly AlertLevel[] = ['expired', 'critical', 'warning', 'notice']

export interface LotProduct {
  id: number
  sku: string
  commercialName: string
  baseUnit: string
}

export interface LotSummary {
  id: number
  product: LotProduct
  lotNumber: string
  expiryDate: string
  quantityOnHand: string
  status: LotStatus
  alertLevel: AlertLevel | null
  daysLeft: number
}

export interface LotDetail extends LotSummary {
  unitCost: string
  blockedReason: string | null
  blockedBy: number | null
  /** Who blocked or recalled the lot (ADR-089). */
  blockedByName: string | null
  blockedAt: string | null
  receivedAt: string
  sellable: boolean
}

export interface ExpiryAlerts {
  expired: LotSummary[]
  critical: LotSummary[]
  warning: LotSummary[]
  notice: LotSummary[]
  thresholds: { notice: number; warning: number; critical: number }
  businessDate: string
}

/** api.md §5.7. */
export interface RecallReport {
  lot: { id: number; status: LotStatus; blockedAt: string | null; blockedBy: number | null }
  buyers: { customerId: number; customerName: string; phone: string | null; documentNumber: string; documentType: string; issuedAt: string; quantityBase: string; unit: string | null }[]
  anonymousSales: { documentNumber: string; issuedAt: string; quantityBase: string }[]
  remainingStockBase: string
}

/** The criteria of `GET /api/lots`; empty strings are "any". */
export interface LotSearch {
  productId: string
  status: string
  alertLevel: string
  expiringWithinDays: string
  hasStock: boolean
  q: string
  page: number
}

export type LotAction = 'block' | 'unblock' | 'recall'

const lotKeys = {
  all: ['inventory', 'lots'] as const,
  list: (search: LotSearch) => ['inventory', 'lots', 'list', search] as const,
  detail: (id: number) => ['inventory', 'lots', id] as const,
  ofProduct: (productId: number) => ['inventory', 'lots', 'product', productId] as const,
  alerts: ['inventory', 'lots', 'alerts'] as const,
}

export function useLots(search: LotSearch) {
  return useQuery({
    queryKey: lotKeys.list(search),
    queryFn: () => {
      const params = new URLSearchParams({ page: String(search.page), size: '20' })
      for (const key of ['productId', 'status', 'alertLevel', 'expiringWithinDays', 'q'] as const) {
        if (search[key].trim() !== '') {
          params.set(key, search[key].trim())
        }
      }
      if (search.hasStock) {
        params.set('hasStock', 'true')
      }
      return apiFetch<{ content: LotSummary[]; page: PageMetadata }>(`/api/lots?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

export function useLot(id: number) {
  return useQuery({ queryKey: lotKeys.detail(id), queryFn: () => apiFetch<LotDetail>(`/api/lots/${id}`) })
}

export function useProductLots(productId: number, enabled = true) {
  return useQuery({
    enabled,
    queryKey: lotKeys.ofProduct(productId),
    queryFn: () => apiFetch<LotSummary[]>(`/api/products/${productId}/lots`),
  })
}

export function useExpiryAlerts(enabled: boolean) {
  return useQuery({ queryKey: lotKeys.alerts, queryFn: () => apiFetch<ExpiryAlerts>('/api/lots/expiry-alerts'), enabled })
}

/** Block or unblock (→ `LotDetail`) or recall (→ `RecallReport`); every lot query is refreshed afterwards. */
export function useLotAction(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ action, reason }: { action: LotAction; reason: string }) =>
      apiFetch<LotDetail | RecallReport>(`/api/lots/${id}/${action}`, { method: 'POST', json: { reason } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: lotKeys.all }),
  })
}

export function useRecallReport(id: number, enabled: boolean) {
  return useQuery({
    queryKey: [...lotKeys.detail(id), 'recall-report'],
    queryFn: () => apiFetch<RecallReport>(`/api/lots/${id}/recall-report`),
    enabled,
  })
}
