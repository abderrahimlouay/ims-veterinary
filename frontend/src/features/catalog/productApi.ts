import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../api/client'
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
  /** From Phase 4 (ADR-089): base-unit quantities, status and nearest expiry computed by the server (ADR-080). */
  sellableQtyBase: string
  totalQtyBase: string
  stockStatus: 'in_stock' | 'low' | 'out'
  nearestExpiry: string | null
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
  targetSpecies: ReferenceItem[]
  withdrawalPeriods: WithdrawalPeriod[]
  createdAt: string
  updatedAt: string
  version: number
}

export interface ProductIngredient {
  ingredient: ReferenceItem
  strength: string | null
}

/** Days `null` = not specified, never 0 (BR-PROD-007). */
export interface WithdrawalPeriod {
  species: ReferenceItem
  meatDays: number | null
  milkDays: number | null
  eggsDays: number | null
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

/** The search criteria of `GET /api/products` (ADR-047, ADR-048); empty strings are "any". */
export interface ProductSearch {
  q: string
  categoryId: string
  manufacturerId: string
  ingredientId: string
  speciesId: string
  includeArchived: boolean
  /** Products with at least one sellable lot (ADR-090). */
  inStockOnly: boolean
  page: number
}

export function useProducts(search: ProductSearch, pageSize = 20) {
  return useQuery({
    queryKey: productKeys.list(search),
    queryFn: ({ signal }) => {
      const params = new URLSearchParams({ page: String(search.page), size: String(pageSize) })
      for (const key of ['q', 'categoryId', 'manufacturerId', 'ingredientId', 'speciesId'] as const) {
        if (search[key].trim() !== '') {
          params.set(key, search[key].trim())
        }
      }
      if (search.includeArchived) {
        params.set('includeArchived', 'true')
      }
      if (search.inStockOnly) {
        params.set('inStockOnly', 'true')
      }
      return apiFetch<{ content: ProductSummary[]; page: PageMetadata }>(`/api/products?${params}`, { signal })
    },
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

export function useReplaceTargetSpecies(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (speciesIds: number[]) =>
      apiFetch<ReferenceItem[]>(`/api/products/${id}/target-species`, { method: 'PUT', json: { speciesIds } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: productKeys.detail(id) }),
  })
}

export function useReplaceWithdrawalPeriods(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (periods: { speciesId: number; meatDays: number | null; milkDays: number | null; eggsDays: number | null }[]) =>
      apiFetch<WithdrawalPeriod[]>(`/api/products/${id}/withdrawal-periods`, { method: 'PUT', json: periods }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: productKeys.detail(id) }),
  })
}

/** Same-ingredient products with sellable stock (FR-PROD-004, ADR-089). */
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

/** `UnitView` (api.md §4.3, ADR-063): `salePrice` stored (2 decimals) or `null`; `effectiveSalePrice` never null. */
export interface ProductUnit {
  id: number
  name: string
  factorToBase: string
  salePrice: string | null
  effectiveSalePrice: string
  sellable: boolean
  version: number
}

/** `salePrice` `null` = derived price; sent on every write, so a PUT without it would clear the stored price. */
export interface UnitWrite {
  name: string
  factorToBase: string
  salePrice: string | null
  sellable: boolean
  version?: number
}

const unitsKey = (productId: number) => [...productKeys.all, 'units', productId] as const

export function useUnits(productId: number) {
  return useQuery({ queryKey: unitsKey(productId), queryFn: () => apiFetch<ProductUnit[]>(`/api/products/${productId}/units`) })
}

/** Adds (`unitId` undefined) or edits a packaging unit; the list is refreshed afterwards. */
export function useSaveUnit(productId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ unitId, unit }: { unitId?: number; unit: UnitWrite }) =>
      apiFetch<ProductUnit>(`/api/products/${productId}/units${unitId === undefined ? '' : `/${unitId}`}`, {
        method: unitId === undefined ? 'POST' : 'PUT',
        json: unit,
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: unitsKey(productId) }),
  })
}

export type BarcodeType = 'EAN13' | 'CODE128' | 'QR'
export const BARCODE_TYPES: readonly BarcodeType[] = ['EAN13', 'CODE128', 'QR']

/** `unit` `null` = the base unit (ADR-057). */
export interface ProductBarcode {
  id: number
  code: string
  codeType: BarcodeType
  unit: ProductUnit | null
}

const barcodesKey = (productId: number) => [...productKeys.all, 'barcodes', productId] as const

export function useBarcodes(productId: number) {
  return useQuery({
    queryKey: barcodesKey(productId),
    queryFn: () => apiFetch<ProductBarcode[]>(`/api/products/${productId}/barcodes`),
  })
}

/** The code is sent exactly as typed or scanned: barcodes are compared exactly (ADR-058). */
export function useAddBarcode(productId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (barcode: { code: string; codeType: BarcodeType; unitId: number | null }) =>
      apiFetch<ProductBarcode>(`/api/products/${productId}/barcodes`, { method: 'POST', json: barcode }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: barcodesKey(productId) }),
  })
}

export function useRemoveBarcode(productId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (barcodeId: number) =>
      apiFetch<void>(`/api/products/${productId}/barcodes/${barcodeId}`, { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: barcodesKey(productId) }),
  })
}

/** `GET /api/products/by-barcode` (BR-UNIT-005): the product, the scanned unit (`null` = base unit), quantity 1. */
export interface ScanResult {
  product: ProductSummary
  unit: ProductUnit | null
  quantity: string
}

/**
 * The scan lookup (frontend.md §4.1, ADR-071): the code goes as a query parameter, exactly as scanned (codes may
 * contain "/" or "%"); `null` when no product carries it.
 */
export async function findByBarcode(code: string): Promise<ScanResult | null> {
  try {
    return await apiFetch<ScanResult>(`/api/products/by-barcode?${new URLSearchParams({ code })}`)
  } catch (error) {
    if (error instanceof ApiError && error.problem.code === 'NOT_FOUND') {
      return null
    }
    throw error
  }
}
