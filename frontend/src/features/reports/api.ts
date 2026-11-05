import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'

/** api.md §4.13 (ADR-147…149): every report as JSON, and as CSV or PDF on the same path. */
type Decimal = string

export interface RevenueReport {
  from: string
  to: string
  groupBy: string
  rows: { key: string | null; code: string | null; label: string | null; revenueHt: Decimal }[]
  totalHt: Decimal
  uninvoicedDeliveryNotes: { count: number; totalHt: Decimal }
}

export interface MarginReport {
  from: string
  to: string
  groupBy: string
  rows: {
    key: string | null
    code: string | null
    label: string | null
    quantityBase: Decimal | null
    revenueHt: Decimal
    cost: Decimal
    margin: Decimal
    marginRate: Decimal | null
  }[]
  totalRevenueHt: Decimal
  totalCost: Decimal
  totalMargin: Decimal
  marginRate: Decimal | null
}

export interface StatisticsReport {
  from: string
  to: string
  groupBy: string
  customerType: string | null
  rows: { key: string | null; code: string | null; label: string | null; documents: number; revenueHt: Decimal }[]
  totalDocuments: number
  totalHt: Decimal
}

export interface ValuationReport {
  date: string
  basis: string
  groupBy: string
  rows: {
    key: string | null
    code: string | null
    label: string | null
    baseUnit: string | null
    quantityBase: Decimal | null
    value: Decimal
    unsellableQuantityBase: Decimal | null
    unsellableValue: Decimal
  }[]
  totalValue: Decimal
  totalUnsellableValue: Decimal
}

interface Ageing {
  notDue: Decimal
  overdue: Decimal[]
  advance: Decimal
  balance: Decimal
  uninvoicedDeliveryNotes: Decimal
}

export interface CustomerBalancesReport {
  date: string
  customerType: string | null
  bucketDays: number[]
  rows: (Ageing & { customerId: number; name: string; phone: string | null; wilaya: string | null })[]
  totals: Ageing
}

export interface PayablesReport {
  date: string
  supplierId: number | null
  rows: {
    invoiceId: number
    supplierId: number
    supplierName: string
    invoiceNumber: string
    invoiceDate: string
    dueDate: string
    daysOverdue: number
    totalTtc: Decimal
    open: Decimal
  }[]
  totalOpen: Decimal
  totalOverdue: Decimal
  unappliedCreditNotes: Decimal
  advances: Decimal
  balance: Decimal
}

export interface CashSessionsReport {
  from: string
  to: string
  cashierId: number | null
  rows: {
    sessionId: number
    cashierId: number
    cashierName: string
    closedById: number | null
    closedByName: string | null
    openedAt: string
    closedAt: string
    openingAmount: Decimal
    expectedAmount: Decimal
    countedAmount: Decimal
    variance: Decimal
  }[]
  sessions: number
  sessionsWithVariance: number
  totalShortage: Decimal
  totalSurplus: Decimal
  totalVariance: Decimal
}

export interface LotTraceability {
  lot: {
    id: number
    lotNumber: string
    expiryDate: string
    status: string
    expired: boolean
    quantityOnHand: Decimal
    product: { id: number; sku: string; name: string; baseUnit: string }
  }
  movements: {
    id: number
    at: string
    type: string
    quantity: Decimal
    user: string
    document: { type: string; id: number | null; number: string } | null
    party: { type: string; id: number | null; name: string | null } | null
  }[]
  suppliers: { id: number; name: string; received: Decimal; returned: Decimal }[]
  customers: { id: number | null; name: string | null; sold: Decimal; returned: Decimal; net: Decimal }[]
}

export type ReportParams = Record<string, string>

/** Empty values are left out, so the server applies its defaults (api.md §4.13). */
export function reportQuery(params: ReportParams, format?: 'csv' | 'pdf'): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== '') {
      search.set(key, value)
    }
  }
  if (format) {
    search.set('format', format)
  }
  return search.toString()
}

export function useReport<T>(path: string, params: ReportParams, enabled = true) {
  return useQuery({
    queryKey: ['reports', path, params],
    queryFn: () => apiFetch<T>(`/api/reports/${path}?${reportQuery(params)}`),
    placeholderData: keepPreviousData,
    enabled,
  })
}
