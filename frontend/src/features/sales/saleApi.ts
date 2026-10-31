import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'

/** api.md §4.10 and §5.5–5.6: sales documents. Amounts, lots and warnings are the server's (BR-SALE-008). */
export type DocType = 'quote' | 'delivery_note' | 'invoice' | 'receipt' | 'credit_note'
export type PaymentMethod = 'cash' | 'cheque' | 'bank_transfer'
export const PAYMENT_METHODS: readonly PaymentMethod[] = ['cash', 'cheque', 'bank_transfer']

export interface SaleWarning {
  code: 'LOT_NEAR_EXPIRY' | 'WITHDRAWAL_PERIOD' | 'LOW_STOCK_AFTER_SALE'
  productId: number
  expiryDate: string | null
  level: string | null
  speciesName: string | null
  meatDays: number | null
  milkDays: number | null
  eggsDays: number | null
}

export interface SaleLine {
  id: number
  productId: number
  productName: string
  lotId: number | null
  lotNumber: string | null
  expiryDate: string | null
  unitId: number | null
  unitName: string
  targetSpeciesId: number | null
  quantity: string
  quantityBase: string
  unitPrice: string
  discountPct: string
  vatRate: string
  lineHt: string
  lineVat: string
  lineTtc: string
  warnings: SaleWarning[]
}

export interface SaleDetail {
  id: number
  docType: DocType
  number: string | null
  status: 'draft' | 'confirmed' | 'cancelled'
  customer: { id: number; name: string } | null
  parentId: number | null
  creditSale: boolean
  dueDate: string | null
  issuedAt: string | null
  createdAt: string
  cancelledAt: string | null
  cancelReason: string | null
  notes: string | null
  totals: { ht: string; discount: string; vat: string; ttc: string }
  lines: SaleLine[]
  payments: { id: number; method: PaymentMethod; amount: string; reference: string | null }[]
  cashSessionId: number | null
  customerBalance: string | null
  version: number
}

export interface DraftWrite {
  docType: DocType
  customerId: number | null
  creditSale: boolean
}

export interface LineWrite {
  productId: number
  unitId: number | null
  quantity: string
  targetSpeciesId: number | null
  lotId?: number | null
  fefoOverrideReason?: string | null
  manualDiscountPct?: string | null
}

export interface PaymentWrite {
  method: PaymentMethod
  amount: string
  reference: string | null
}

export interface CreditBalance {
  balance: string
  uninvoicedDeliveryNotes: string
  creditLimit: string | null
  availableCredit: string | null
}

const post = <T,>(path: string, json: unknown, headers?: Record<string, string>) =>
  apiFetch<T>(path, { method: 'POST', json, headers })

export const fetchSale = (id: number) => apiFetch<SaleDetail>(`/api/sales/${id}`)
export const createDraft = (draft: DraftWrite) => post<SaleDetail>('/api/sales', draft)
export const updateDraft = (sale: SaleDetail, creditSale: boolean) =>
  apiFetch<SaleDetail>(`/api/sales/${sale.id}`, {
    method: 'PUT',
    json: { customerId: sale.customer?.id ?? null, creditSale, dueDate: sale.dueDate, notes: sale.notes, version: sale.version },
  })
export const abandonDraft = (id: number) => apiFetch<void>(`/api/sales/${id}`, { method: 'DELETE' })
export const addLine = (id: number, line: LineWrite) => post<SaleDetail>(`/api/sales/${id}/lines`, line)
export const replaceLine = (id: number, lineId: number, line: LineWrite) =>
  apiFetch<SaleDetail>(`/api/sales/${id}/lines/${lineId}`, { method: 'PUT', json: line })
export const removeLine = (id: number, lineId: number) =>
  apiFetch<SaleDetail>(`/api/sales/${id}/lines/${lineId}`, { method: 'DELETE' })
/** `key`: one per document and payment choice, so a retry never sells twice (BR-SALE-016). */
export const confirmSale = (id: number, payments: PaymentWrite[], key: string) =>
  post<SaleDetail>(`/api/sales/${id}/confirm`, { payments }, { 'Idempotency-Key': key })

export function useCreditBalance(customerId: number | null, enabled: boolean) {
  return useQuery({
    enabled: enabled && customerId !== null,
    queryKey: ['customers', customerId, 'balance'],
    queryFn: () => apiFetch<CreditBalance>(`/api/customers/${customerId}/balance`),
    staleTime: 0,
  })
}

/** api.md §4.12: the user's own session; the expected amount is the server's (ADR-118). */
export interface CashSession {
  id: number
  status: 'open' | 'closed'
  openedAt: string
  openingAmount: string
  expectedAmount: string
  countedAmount: string | null
  varianceAmount: string | null
  version: number
}

const SESSION_KEY = ['cash-sessions', 'current'] as const

/** `null` when the user has no open session (204). */
export function useCurrentSession(enabled: boolean) {
  return useQuery({
    enabled,
    queryKey: SESSION_KEY,
    queryFn: async () => (await apiFetch<CashSession | undefined>('/api/cash-sessions/current')) ?? null,
  })
}

export function useOpenSession() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (openingAmount: string) => post<CashSession>('/api/cash-sessions/open', { openingAmount }),
    onSuccess: () => client.invalidateQueries({ queryKey: SESSION_KEY }),
  })
}

export function useCloseSession() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, countedAmount }: { id: number; countedAmount: string }) =>
      post<CashSession>(`/api/cash-sessions/${id}/close`, { countedAmount }),
    onSuccess: () => client.invalidateQueries({ queryKey: SESSION_KEY }),
  })
}

export const invalidateSession = (client: ReturnType<typeof useQueryClient>) =>
  client.invalidateQueries({ queryKey: SESSION_KEY })

export interface SaleSummary {
  id: number
  docType: DocType
  number: string | null
  status: SaleDetail['status']
  customer: { id: number; name: string } | null
  creditSale: boolean
  issuedAt: string | null
  createdAt: string
  totalTtc: string
  version: number
}

export interface SaleFilters {
  docType: string
  status: string
  number: string
  page: number
}

type Page<T> = { content: T[]; page: PageMetadata }

const SALES_KEY = ['sales'] as const

export function useSales(filters: SaleFilters) {
  return useQuery({
    queryKey: [...SALES_KEY, 'list', filters],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(filters.page) })
      for (const key of ['docType', 'status', 'number'] as const) {
        if (filters[key].trim() !== '') {
          params.set(key, filters[key].trim())
        }
      }
      return apiFetch<Page<SaleSummary>>(`/api/sales?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

export function useCustomerSales(customerId: number, page: number, enabled: boolean) {
  return useQuery({
    enabled,
    queryKey: [...SALES_KEY, 'customer', customerId, page],
    queryFn: () => apiFetch<Page<SaleSummary>>(`/api/customers/${customerId}/sales?page=${page}`),
    placeholderData: keepPreviousData,
  })
}

export function useSale(id: number) {
  return useQuery({ queryKey: [...SALES_KEY, id], queryFn: () => fetchSale(id) })
}

export function useConvertSale(id: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (targetType: DocType) => post<SaleDetail>(`/api/sales/${id}/convert`, { targetType }),
    onSuccess: () => client.invalidateQueries({ queryKey: SALES_KEY }),
  })
}

export function useCancelSale(id: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (reason: string) => post<SaleDetail>(`/api/sales/${id}/cancel`, { reason }),
    onSuccess: () => client.invalidateQueries({ queryKey: SALES_KEY }),
  })
}

export function useAbandonSale(id: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => abandonDraft(id),
    onSuccess: () => client.invalidateQueries({ queryKey: SALES_KEY }),
  })
}
