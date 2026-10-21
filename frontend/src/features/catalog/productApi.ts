import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'
import type { ReferenceItem } from './referenceApi'

/** api.md §4.3. Decimals are strings (ADR-021): prices 4 decimals, quantities 3, VAT 2. */
export interface ProductSummary {
  id: number
  sku: string
  commercialName: string
  category: ReferenceItem | null
  baseUnit: string
  salePrice: string
  archived: boolean
}

export interface ProductDetail extends ProductSummary {
  dosageForm: ReferenceItem | null
  manufacturer: ReferenceItem | null
  divisible: boolean
  vatRate: string
  purchasePrice: string
  minStock: string
  maxStock: string | null
  reorderPoint: string
  ingredients: ProductIngredient[]
  createdAt: string
  updatedAt: string
  version: number
}

export interface ProductIngredient {
  ingredient: ReferenceItem
  strength: string | null
}

/** `ProductWriteRequest` (api.md §4.3). */
export interface ProductWrite {
  sku: string
  commercialName: string
  categoryId: number | null
  dosageFormId: number | null
  manufacturerId: number | null
  baseUnit: string
  divisible: boolean
  vatRate: string | null
  minStock: string
  maxStock: string | null
  reorderPoint: string
  /** Sent only with `price.edit`; omitted, the server keeps the current prices (BR-PROD-006). */
  purchasePrice?: string
  salePrice?: string
  version?: number
}

export const productKeys = {
  all: ['catalog', 'products'] as const,
  list: (filter: object) => ['catalog', 'products', 'list', filter] as const,
  detail: (id: number) => ['catalog', 'products', id] as const,
}

export function useProducts(filter: { includeArchived: boolean; page: number }) {
  return useQuery({
    queryKey: productKeys.list(filter),
    queryFn: () =>
      apiFetch<{ content: ProductSummary[]; page: PageMetadata }>(
        `/api/products?includeArchived=${filter.includeArchived}&page=${filter.page}&size=20`,
      ),
    placeholderData: keepPreviousData,
  })
}

export function useProduct(id: number) {
  return useQuery({ queryKey: productKeys.detail(id), queryFn: () => apiFetch<ProductDetail>(`/api/products/${id}`) })
}

function useProductMutation<V>(mutationFn: (variables: V) => Promise<ProductDetail>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: (product) => {
      queryClient.setQueryData(productKeys.detail(product.id), product)
      void queryClient.invalidateQueries({ queryKey: [...productKeys.all, 'list'] })
    },
  })
}

export function useCreateProduct() {
  return useProductMutation((product: ProductWrite) => apiFetch<ProductDetail>('/api/products', { method: 'POST', json: product }))
}

export function useUpdateProduct(id: number) {
  return useProductMutation((product: ProductWrite) =>
    apiFetch<ProductDetail>(`/api/products/${id}`, { method: 'PUT', json: product }),
  )
}

/** Replaces the ingredient set (FR-PROD-004); the detail is refreshed afterwards. */
export function useReplaceIngredients(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (lines: { ingredientId: number; strength: string | null }[]) =>
      apiFetch<ProductIngredient[]>(`/api/products/${id}/ingredients`, { method: 'PUT', json: lines }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: productKeys.detail(id) })
      void queryClient.invalidateQueries({ queryKey: [...productKeys.all, 'substitutes'] })
    },
  })
}

/** Same-ingredient products (FR-PROD-004), without stock until Phase 4. */
export function useSubstitutes(id: number) {
  return useQuery({
    queryKey: [...productKeys.all, 'substitutes', id],
    queryFn: () => apiFetch<ProductSummary[]>(`/api/products/${id}/substitutes`),
  })
}

export function useSetArchived(id: number) {
  return useProductMutation((archived: boolean) =>
    apiFetch<ProductDetail>(`/api/products/${id}/${archived ? 'archive' : 'restore'}`, { method: 'POST' }),
  )
}
