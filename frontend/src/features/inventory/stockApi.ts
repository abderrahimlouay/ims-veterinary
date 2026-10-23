import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'
import type { LotDetail } from './lotApi'

/** api.md §4.5 `StockLevel` (ADR-080): computed on read with the current expiry policy. */
export type StockStatus = 'in_stock' | 'low' | 'out'
export const STOCK_STATUSES: readonly StockStatus[] = ['in_stock', 'low', 'out']

export interface StockLevel {
  product: { id: number; sku: string; commercialName: string; baseUnit: string; archived: boolean }
  totalQty: string
  sellableQty: string
  minStock: string
  maxStock: string | null
  reorderPoint: string
  status: StockStatus
  belowReorder: boolean
  nearestSellableExpiry: string | null
}

/** The ten movement types of ADR-081; the four manual adjustments of ADR-085 come first. */
export const ADJUSTMENT_TYPES = ['adjustment_breakage', 'adjustment_expiry', 'adjustment_theft', 'adjustment_internal_use'] as const
export type AdjustmentType = (typeof ADJUSTMENT_TYPES)[number]
export const MOVEMENT_TYPES = [
  'purchase_receipt',
  'sale',
  'customer_return',
  'supplier_return',
  ...ADJUSTMENT_TYPES,
  'adjustment_inventory',
  'opening_stock',
] as const

/** api.md §4.5 `MovementEntry`: immutable, `quantity` signed (BR-STOCK-003/004). */
export interface MovementEntry {
  id: number
  lot: { id: number; lotNumber: string; expiryDate: string }
  product: { id: number; sku: string; commercialName: string; baseUnit: string }
  type: string
  quantity: string
  referenceType: string | null
  referenceId: number | null
  reason: string | null
  user: { id: number; fullName: string }
  createdAt: string
}

/** api.md §4.5 `IntegrityRun` (ADR-086); `triggeredBy` null for the nightly job. */
export interface IntegrityRun {
  id: number
  startedAt: string
  finishedAt: string | null
  lotsChecked: number
  mismatchCount: number
  mismatches: { lotId: number; lotNumber: string; productId: number; balance: string; ledgerSum: string }[]
  triggeredBy: { id: number; fullName: string } | null
}

/** The criteria of `GET /api/inventory/stock`; empty strings are "any". */
export interface StockSearch {
  q: string
  status: string
  belowReorder: boolean
  page: number
}

/** The criteria of `GET /api/inventory/movements` (dates in the business time zone). */
export interface MovementSearch {
  productId: string
  lotId: string
  type: string
  from: string
  to: string
  page: number
}

export interface AdjustmentInput {
  lotId: number
  type: AdjustmentType
  quantity: string
  reason: string
}

export interface OpeningInput {
  productId: number
  lotNumber: string
  expiryDate: string
  quantity: string
  unitId: number | null
  unitCost: string
}

export const stockKeys = {
  all: ['inventory', 'stock'] as const,
  levels: (search: object) => ['inventory', 'stock', 'levels', search] as const,
  movements: (search: object) => ['inventory', 'stock', 'movements', search] as const,
  integrity: ['inventory', 'stock', 'integrity'] as const,
}

type Page<T> = { content: T[]; page: PageMetadata }

function query(params: Record<string, string | boolean | number>): URLSearchParams {
  const result = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== '' && value !== false) {
      result.set(key, String(value).trim())
    }
  }
  return result
}

export function useStockLevels(search: StockSearch, size = 20) {
  return useQuery({
    queryKey: stockKeys.levels({ ...search, size }),
    queryFn: ({ signal }) =>
      apiFetch<Page<StockLevel>>(`/api/inventory/stock?${query({ ...search, size })}`, { signal }),
    placeholderData: keepPreviousData,
  })
}

export function useMovements(search: MovementSearch, enabled = true) {
  return useQuery({
    queryKey: stockKeys.movements(search),
    queryFn: ({ signal }) =>
      apiFetch<Page<MovementEntry>>(`/api/inventory/movements?${query({ ...search, size: 20 })}`, { signal }),
    placeholderData: keepPreviousData,
    enabled,
  })
}

/** Every stock and lot query is refreshed after a movement. */
function useStockMutation<V, R>(mutationFn: (variables: V) => Promise<R>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: stockKeys.all }),
        queryClient.invalidateQueries({ queryKey: ['inventory', 'lots'] }),
        queryClient.invalidateQueries({ queryKey: ['catalog', 'products'] }),
      ]),
  })
}

export function useAdjustment() {
  return useStockMutation((input: AdjustmentInput) =>
    apiFetch<MovementEntry>('/api/inventory/adjustments', { method: 'POST', json: input }),
  )
}

export function useOpeningStock() {
  return useStockMutation((input: OpeningInput) =>
    apiFetch<{ lot: LotDetail; movement: MovementEntry }>('/api/inventory/opening-stock', { method: 'POST', json: input }),
  )
}

/** The latest stored run, `null` when none exists yet (204). */
export function useIntegrityRun(enabled: boolean) {
  return useQuery({
    queryKey: stockKeys.integrity,
    queryFn: async () => (await apiFetch<IntegrityRun | undefined>('/api/inventory/integrity-check')) ?? null,
    enabled,
    staleTime: 5 * 60_000,
  })
}

export function useRunIntegrityCheck() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch<IntegrityRun>('/api/inventory/integrity-check', { method: 'POST' }),
    onSuccess: (run) => queryClient.setQueryData(stockKeys.integrity, run),
  })
}
