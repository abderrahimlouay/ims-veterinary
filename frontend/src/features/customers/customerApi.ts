import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'

/** api.md §4.9 customers, their prices and the customer categories (ADR-108…114). */
export type CustomerType = 'breeder' | 'veterinarian' | 'other'
export const CUSTOMER_TYPES: readonly CustomerType[] = ['breeder', 'veterinarian', 'other']

export interface CategoryRef {
  id: number
  name: string
  discountPct: string
}

export interface CustomerSummary {
  id: number
  name: string
  customerType: CustomerType
  category: CategoryRef | null
  wilaya: string | null
  commune: string | null
  phone: string | null
  active: boolean
}

/** `creditLimit`: `null` no limit, `"0.00"` cash only, else the limit (ADR-108). */
export interface CustomerDetail extends CustomerSummary {
  farmingType: string | null
  address: string | null
  email: string | null
  taxId: string | null
  paymentTermsDays: number
  veterinarian: { id: number; name: string } | null
  creditLimit: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface CustomerWrite {
  name: string
  customerType: CustomerType
  categoryId: number | null
  farmingType: string | null
  wilaya: string | null
  commune: string | null
  address: string | null
  phone: string | null
  email: string | null
  taxId: string | null
  paymentTermsDays: number | null
  veterinarianId: number | null
}

/** Ids and fields only: names and phones never come back (ADR-114). */
export interface SimilarCustomer {
  customerId: number
  field: 'name' | 'phone' | 'taxId'
}

export interface CustomerPrice {
  product: { id: number; sku: string; commercialName: string; baseUnit: string; archived: boolean }
  unitPrice: string
  updatedAt: string
  version: number
}

export interface CustomerCategory {
  id: number
  name: string
  discountPct: string
  updatedAt: string
  version: number
}

export interface CustomerFilters {
  q: string
  type: string
  active: string
  page: number
}

type Page<T> = { content: T[]; page: PageMetadata }

const keys = {
  all: ['customers'] as const,
  list: (f: CustomerFilters, size: number) => ['customers', 'list', f, size] as const,
  detail: (id: number) => ['customers', id] as const,
  prices: (id: number) => ['customers', id, 'prices'] as const,
  categories: ['customer-categories'] as const,
}

/** `type` and `active`: '' for all. The search text goes in the query string, as for every list. */
export function useCustomers(filters: CustomerFilters, size = 20, enabled = true) {
  return useQuery({
    enabled,
    queryKey: keys.list(filters, size),
    queryFn: () => {
      const params = new URLSearchParams({ page: String(filters.page), size: String(size) })
      if (filters.q.trim() !== '') {
        params.set('q', filters.q.trim())
      }
      if (filters.type !== '') {
        params.set('type', filters.type)
      }
      if (filters.active !== '') {
        params.set('active', filters.active)
      }
      return apiFetch<Page<CustomerSummary>>(`/api/customers?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

export function useCustomer(id: number) {
  return useQuery({ queryKey: keys.detail(id), queryFn: () => apiFetch<CustomerDetail>(`/api/customers/${id}`) })
}

/** Before saving: possible duplicates by name, phone or tax id, sent in a POST body (ADR-110, ADR-114). */
export function useSimilarCustomers() {
  return useMutation({
    mutationFn: (probe: { name: string | null; phone: string | null; taxId: string | null; excludeId: number | null }) =>
      apiFetch<SimilarCustomer[]>('/api/customers/similar', { method: 'POST', json: probe }),
  })
}

/** Creates (`id` undefined) or updates with the current version; the credit limit is never part of it (ADR-109). */
export function useSaveCustomer() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, customer, version }: { id?: number; customer: CustomerWrite; version?: number }) =>
      apiFetch<CustomerDetail>(id === undefined ? '/api/customers' : `/api/customers/${id}`, {
        method: id === undefined ? 'POST' : 'PUT',
        json: id === undefined ? customer : { ...customer, version },
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.all }),
  })
}

export function useSetCustomerActive(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (active: boolean) =>
      apiFetch<CustomerDetail>(`/api/customers/${id}/${active ? 'activate' : 'deactivate'}`, { method: 'POST' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.all }),
  })
}

/** `creditLimit` is always sent, `null` included: a missing field is refused, never read as "no limit" (ADR-108). */
export function useChangeCreditLimit(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ creditLimit, version }: { creditLimit: string | null; version: number }) =>
      apiFetch<CustomerDetail>(`/api/customers/${id}/credit-limit`, { method: 'PUT', json: { creditLimit, version } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.all }),
  })
}

export function useCustomerPrices(id: number) {
  return useQuery({ queryKey: keys.prices(id), queryFn: () => apiFetch<CustomerPrice[]>(`/api/customers/${id}/prices`) })
}

/** One price at a time (ADR-111): `version` null to create, the current version to change. */
export function useSetCustomerPrice(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ productId, unitPrice, version }: { productId: number; unitPrice: string; version: number | null }) =>
      apiFetch<CustomerPrice>(`/api/customers/${id}/prices/${productId}`, { method: 'PUT', json: { unitPrice, version } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.prices(id) }),
  })
}

export function useRemoveCustomerPrice(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ productId, version }: { productId: number; version: number }) =>
      apiFetch(`/api/customers/${id}/prices/${productId}?version=${version}`, { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.prices(id) }),
  })
}

export function useCustomerCategories(enabled = true) {
  return useQuery({ enabled, queryKey: keys.categories, queryFn: () => apiFetch<CustomerCategory[]>('/api/customer-categories') })
}

/** Creates (`id` undefined) or renames / changes the discount with the current version (ADR-112). */
export function useSaveCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, name, discountPct, version }: { id?: number; name: string; discountPct: string; version?: number }) =>
      apiFetch<CustomerCategory>(id === undefined ? '/api/customer-categories' : `/api/customer-categories/${id}`, {
        method: id === undefined ? 'POST' : 'PUT',
        json: id === undefined ? { name, discountPct } : { name, discountPct, version },
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.categories })
      await queryClient.invalidateQueries({ queryKey: keys.all })
    },
  })
}
