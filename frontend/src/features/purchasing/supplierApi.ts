import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'

/** api.md §4.6 suppliers and their price history (ADR-100, ADR-106). */
export type SupplierKind = 'laboratory' | 'wholesaler'
export const SUPPLIER_KINDS: readonly SupplierKind[] = ['laboratory', 'wholesaler']

export interface SupplierDetail {
  id: number
  name: string
  kind: SupplierKind
  taxId: string | null
  phone: string | null
  email: string | null
  address: string | null
  paymentTermsDays: number
  active: boolean
  createdAt: string
  updatedAt: string
  version: number
}

export interface SupplierWrite {
  name: string
  kind: SupplierKind
  taxId: string | null
  phone: string | null
  email: string | null
  address: string | null
  paymentTermsDays: number | null
}

/** Price per base unit; `cancelled` when the receipt that appended it was cancelled (shown struck through). */
export interface PriceEntry {
  id: number
  product: { id: number; sku: string; commercialName: string; baseUnit: string }
  unitPrice: string
  discountPct: string
  validFrom: string
  createdAt: string
  createdBy: { id: number; fullName: string }
  receiptId: number | null
  cancelled: boolean
}

export interface PriceWrite {
  productId: number
  unitPrice: string
  discountPct: string | null
  validFrom: string | null
}

type Page<T> = { content: T[]; page: PageMetadata }

const supplierKeys = {
  all: ['suppliers'] as const,
  list: (q: string, active: string, page: number, size: number) => ['suppliers', 'list', q, active, page, size] as const,
  detail: (id: number) => ['suppliers', id] as const,
  prices: (id: number, page: number) => ['suppliers', id, 'prices', page] as const,
}

/** `active`: '' all, 'true' or 'false'. */
export function useSuppliers(q: string, active: string, page: number, size = 20) {
  return useQuery({
    queryKey: supplierKeys.list(q, active, page, size),
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: String(size) })
      if (q.trim() !== '') {
        params.set('q', q.trim())
      }
      if (active !== '') {
        params.set('active', active)
      }
      return apiFetch<Page<SupplierDetail>>(`/api/suppliers?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

export function useSupplier(id: number) {
  return useQuery({ queryKey: supplierKeys.detail(id), queryFn: () => apiFetch<SupplierDetail>(`/api/suppliers/${id}`) })
}

/** Creates (`id` undefined) or updates with the current version (ADR-027). */
export function useSaveSupplier() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, supplier, version }: { id?: number; supplier: SupplierWrite; version?: number }) =>
      apiFetch<SupplierDetail>(id === undefined ? '/api/suppliers' : `/api/suppliers/${id}`, {
        method: id === undefined ? 'POST' : 'PUT',
        json: id === undefined ? supplier : { ...supplier, version },
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: supplierKeys.all }),
  })
}

export function useSetSupplierActive(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (active: boolean) =>
      apiFetch<SupplierDetail>(`/api/suppliers/${id}/${active ? 'activate' : 'deactivate'}`, { method: 'POST' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: supplierKeys.all }),
  })
}

export function useSupplierPrices(id: number, page: number) {
  return useQuery({
    queryKey: supplierKeys.prices(id, page),
    queryFn: () => apiFetch<Page<PriceEntry>>(`/api/suppliers/${id}/prices?page=${page}&size=20`),
    placeholderData: keepPreviousData,
  })
}

export function useAddPrice(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (price: PriceWrite) => apiFetch<PriceEntry>(`/api/suppliers/${id}/prices`, { method: 'POST', json: price }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: supplierKeys.all }),
  })
}
