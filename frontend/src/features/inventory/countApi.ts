import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'

/** api.md §4.5 counts (W5, ADR-072…075). */
export type CountType = 'full' | 'cycle'
export type CountStatus = 'open' | 'closed' | 'cancelled'
export const COUNT_STATUSES: readonly CountStatus[] = ['open', 'closed', 'cancelled']

export interface UserRef {
  id: number
  fullName: string
}

export interface CountSummary {
  id: number
  type: CountType
  status: CountStatus
  startedAt: string
  startedBy: UserRef
  closedAt: string | null
  lineCount: number
  countedCount: number
}

/**
 * `varianceQty` = counted − balance when counted (ADR-072); `movementsSinceCount` is the signed sum of the lot's
 * movements after that moment; `foundLot` is a lot unknown to the system, created at regularisation (ADR-074).
 */
export interface CountLine {
  id: number
  product: { id: number; sku: string; commercialName: string; baseUnit: string }
  lot: { id: number; lotNumber: string; expiryDate: string } | null
  foundLot: { lotNumber: string; expiryDate: string; unitCost: string } | null
  theoreticalQty: string
  countedQty: string | null
  countedAt: string | null
  countedBy: UserRef | null
  balanceAtCount: string | null
  varianceQty: string | null
  movementsSinceCount: string | null
  currentBalance: string | null
  regularized: boolean
  adjustmentMovementId: number | null
}

export interface CountDetail {
  id: number
  type: CountType
  status: CountStatus
  scope: { categoryId: number | null; productIds: number[] }
  notes: string | null
  startedAt: string
  startedBy: UserRef
  closedAt: string | null
  closedBy: UserRef | null
  version: number
  lines: CountLine[]
}

export interface OpenCountInput {
  type: CountType
  categoryId?: number
  productIds?: number[]
  notes?: string
}

/** An existing lot of the scope (`lotId`) or a lot unknown to the system (the other fields, ADR-074). */
export type AddLineInput =
  | { lotId: number; countedQty: string }
  | { productId: number; lotNumber: string; expiryDate: string; unitCost: string; countedQty: string }

const countKeys = {
  all: ['inventory', 'counts'] as const,
  list: (status: string, page: number) => ['inventory', 'counts', 'list', status, page] as const,
  detail: (id: number) => ['inventory', 'counts', id] as const,
}

export function useCounts(status: string, page: number) {
  return useQuery({
    queryKey: countKeys.list(status, page),
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20' })
      if (status !== '') {
        params.set('status', status)
      }
      return apiFetch<{ content: CountSummary[]; page: PageMetadata }>(`/api/inventory/counts?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

export function useCount(id: number) {
  return useQuery({ queryKey: countKeys.detail(id), queryFn: () => apiFetch<CountDetail>(`/api/inventory/counts/${id}`) })
}

export function useOpenCount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: OpenCountInput) => apiFetch<CountDetail>('/api/inventory/counts', { method: 'POST', json: input }),
    onSuccess: (count) => {
      queryClient.setQueryData(countKeys.detail(count.id), count)
      return queryClient.invalidateQueries({ queryKey: [...countKeys.all, 'list'] })
    },
  })
}

/** Saving a line takes a new watermark (ADR-072); the count is reloaded so every line shows current figures. */
export function useEnterCounted(countId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ lineId, countedQty }: { lineId: number; countedQty: string }) =>
      apiFetch<CountLine>(`/api/inventory/counts/${countId}/lines/${lineId}`, { method: 'PUT', json: { countedQty } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: countKeys.detail(countId) }),
  })
}

export function useAddLine(countId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: AddLineInput) =>
      apiFetch<CountLine>(`/api/inventory/counts/${countId}/lines`, { method: 'POST', json: input }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: countKeys.detail(countId) }),
  })
}

/** Regularise (ADR-075) or cancel; stock, lots and the count lists are refreshed afterwards. */
function useCloseCount(countId: number, action: 'regularize' | 'cancel') {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch<CountDetail>(`/api/inventory/counts/${countId}/${action}`, { method: 'POST', json: {} }),
    onSuccess: (count) => {
      queryClient.setQueryData(countKeys.detail(countId), count)
      return Promise.all([
        queryClient.invalidateQueries({ queryKey: [...countKeys.all, 'list'] }),
        queryClient.invalidateQueries({ queryKey: ['inventory', 'stock'] }),
        queryClient.invalidateQueries({ queryKey: ['inventory', 'lots'] }),
      ])
    },
  })
}

export function useRegularize(countId: number) {
  return useCloseCount(countId, 'regularize')
}

export function useCancelCount(countId: number) {
  return useCloseCount(countId, 'cancel')
}
