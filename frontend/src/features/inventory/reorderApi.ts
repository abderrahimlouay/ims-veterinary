import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'

/** api.md §4.5 reorder proposals (W10, ADR-076…079). */
export type ProposalStatus = 'pending' | 'converted' | 'dismissed'
export const PROPOSAL_STATUSES: readonly ProposalStatus[] = ['pending', 'converted', 'dismissed']

export interface ProposalEntry {
  id: number
  product: { id: number; sku: string; commercialName: string; baseUnit: string; divisible: boolean }
  currentQty: string
  suggestedQty: string
  reorderPoint: string
  maxStock: string | null
  status: ProposalStatus
  createdAt: string
  updatedAt: string
  dismissedAt: string | null
  dismissedBy: { id: number; fullName: string } | null
}

export interface GenerationResult {
  created: number
  updated: number
  thresholdsIncomplete: { id: number; sku: string; commercialName: string }[]
}

const reorderKeys = {
  all: ['inventory', 'reorder'] as const,
  list: (status: string, page: number, size: number) => ['inventory', 'reorder', status, page, size] as const,
}

export function useProposals(status: string, page: number, size = 20) {
  return useQuery({
    queryKey: reorderKeys.list(status, page, size),
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: String(size) })
      if (status !== '') {
        params.set('status', status)
      }
      return apiFetch<{ content: ProposalEntry[]; page: PageMetadata }>(`/api/inventory/reorder-proposals?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

export function useGenerateProposals() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch<GenerationResult>('/api/inventory/reorder-proposals/generate', { method: 'POST' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: reorderKeys.all }),
  })
}

export function useDismissProposal() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => apiFetch<ProposalEntry>(`/api/inventory/reorder-proposals/${id}/dismiss`, { method: 'POST' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: reorderKeys.all }),
  })
}
