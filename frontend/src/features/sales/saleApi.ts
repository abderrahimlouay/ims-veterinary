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
  /** What customers returned of the line (ADR-131). */
  returnedQuantityBase: string
  /** The line's share of the global discount, excl. tax; `lineHt` is after it (ADR-175). */
  globalDiscountHt: string
  /** The line's total before the global discount: the lines add up to the sub-total shown above it (ADR-175). */
  lineTtcBeforeGlobalDiscount: string
}

export type GlobalDiscountKind = 'percent' | 'amount'

/** ADR-175: as entered, and what it took off excl. and incl. tax. */
export interface GlobalDiscount {
  kind: GlobalDiscountKind
  value: string
  ht: string
  ttc: string
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
  /** `stampDuty` and `netToPay` (TTC + stamp duty): ADR-172; `ht`, `vat`, `ttc` after the global discount (ADR-175). */
  totals: {
    ht: string
    discount: string
    vat: string
    ttc: string
    stampDuty: string
    netToPay: string
    globalDiscount: GlobalDiscount | null
  }
  lines: SaleLine[]
  payments: { id: number; method: PaymentMethod; amount: string; reference: string | null }[]
  cashSessionId: number | null
  customerBalance: string | null
  /** The document's customer returns and their credit notes, oldest first (ADR-131). */
  returns: { id: number; number: string; creditNoteId: number; creditNoteNumber: string; creditNoteTtc: string }[]
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

/** What confirming would charge as stamp duty on `cashPart` of the draft's TTC (ADR-172); the server computes it again. */
export interface StampDutyPreview {
  cashPart: string
  stampDuty: string
  netToPay: string
}

export function useStampDuty(saleId: number | null, ttc: string, cashPart: string | null) {
  return useQuery({
    queryKey: ['sales', saleId, 'stamp-duty', ttc, cashPart],
    queryFn: () => apiFetch<StampDutyPreview>(`/api/sales/${saleId}/stamp-duty?cash=${encodeURIComponent(cashPart ?? '')}`),
    enabled: saleId !== null && cashPart !== null,
    retry: false,
  })
}
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
/** ADR-175: `null` removes the global discount; giving one needs `sale.discount`. */
export const giveGlobalDiscount = (id: number, discount: { kind: GlobalDiscountKind; value: string } | null) =>
  apiFetch<SaleDetail>(`/api/sales/${id}/global-discount`, {
    method: 'PUT',
    json: discount ?? { kind: null, value: null },
  })
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
