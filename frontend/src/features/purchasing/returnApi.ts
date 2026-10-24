import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'

/** api.md §4.11 supplier returns (W4, ADR-101): stock only. */
export interface ReturnLine {
  id: number
  product: { id: number; sku: string; commercialName: string; baseUnit: string }
  lotId: number
  lotNumber: string
  expiryDate: string
  quantityBase: string
  movementId: number
}

export interface SupplierReturnSummary {
  id: number
  number: string
  supplier: { id: number; name: string; active: boolean }
  reason: string
  createdAt: string
}

export interface SupplierReturnDetail extends SupplierReturnSummary {
  createdBy: { id: number; fullName: string }
  lines: ReturnLine[]
}

export interface ReturnWrite {
  supplierId: number | null
  reason: string
  lines: { lotId: number; quantity: string; unitId: number | null }[]
}

const returnKeys = {
  all: ['supplier-returns'] as const,
  list: (supplierId: string, page: number) => ['supplier-returns', 'list', supplierId, page] as const,
  detail: (id: number) => ['supplier-returns', id] as const,
}

export function useSupplierReturns(supplierId: string, page: number) {
  return useQuery({
    queryKey: returnKeys.list(supplierId, page),
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20' })
      if (supplierId !== '') {
        params.set('supplierId', supplierId)
      }
      return apiFetch<{ content: SupplierReturnSummary[]; page: PageMetadata }>(`/api/returns/supplier?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

export function useSupplierReturn(id: number) {
  return useQuery({ queryKey: returnKeys.detail(id), queryFn: () => apiFetch<SupplierReturnDetail>(`/api/returns/supplier/${id}`) })
}

export function useCreateReturn() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (supplierReturn: ReturnWrite) =>
      apiFetch<SupplierReturnDetail>('/api/returns/supplier', { method: 'POST', json: supplierReturn }),
    onSuccess: () => queryClient.invalidateQueries(),
  })
}
