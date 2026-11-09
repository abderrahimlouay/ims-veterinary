import type { TFunction } from 'i18next'
import { formatDate, formatDateTime, formatMoney, formatQuantity } from '../../lib/format'
import type { ChartPoint } from '../../components/ColumnChart'
import type {
  CashSessionsReport,
  CustomerBalancesReport,
  LotTraceability,
  MarginReport,
  PayablesReport,
  RevenueReport,
  StatisticsReport,
  ValuationReport,
} from './api'

export type Filter = 'period' | 'customerType' | 'basis' | 'supplierId' | 'cashierId' | 'lotId'

export interface Cell {
  text: string
  num?: boolean
}

export interface Column {
  label: string
  num?: boolean
}

export interface Section {
  heading?: string
  columns: Column[]
  rows: Cell[][]
  totals: Cell[][]
}

/**
 * One report of api.md §4.13: its path, permission, filters and groupings, and how its JSON becomes tables. The same
 * figures as the CSV and PDF (ADR-149); every amount comes from the server (BR-SALE-008).
 */
export interface ReportDefinition {
  key: string
  path: string
  permission: string
  filters: Filter[]
  groups?: string[]
  /** Required parameters; the report is not requested until they are set. */
  required?: string[]
  sections(data: unknown, t: TFunction): Section[]
  /** A column chart above the table when the report is grouped by date (ADR-164); screen only. */
  chart?(data: unknown, t: TFunction): { title: string; points: ChartPoint[] } | null
}

const text = (value: string | number | null | undefined): Cell => ({ text: value === null || value === undefined ? '' : String(value) })
const money = (value: string | null): Cell => ({ text: value === null ? '' : formatMoney(value), num: true })
const quantity = (value: string | null): Cell => ({ text: value === null ? '' : formatQuantity(value), num: true })
const count = (value: number): Cell => ({ text: String(value), num: true })
const percent = (value: string | null): Cell => ({ text: value === null ? '—' : `${value.replace('.', ',')} %`, num: true })
const col = (label: string, num = false): Column => ({ label, num })
const blank: Cell = { text: '' }

/** A day `AAAA-MM-JJ` or a month `AAAA-MM` as the tables show it. */
export function dateKey(key: string | null): string {
  if (key === null) {
    return ''
  }
  return key.length === 10 ? formatDate(key) : `${key.substring(5)}/${key.substring(0, 4)}`
}

function groupColumns(groupBy: string, t: TFunction): Column[] {
  switch (groupBy) {
    case 'product':
      return [col(t('reports.col.code')), col(t('reports.col.product'))]
    case 'category':
      return [col(t('reports.col.category'))]
    case 'day':
      return [col(t('reports.col.date'))]
    default:
      return [col(t('reports.col.month'))]
  }
}

function groupCells(groupBy: string, row: { key: string | null; code: string | null; label: string | null }, t: TFunction): Cell[] {
  switch (groupBy) {
    case 'product':
      return [text(row.code), text(row.label)]
    case 'category':
      return [text(row.label ?? t('reports.noCategory'))]
    default:
      return [text(dateKey(row.key))]
  }
}

/** Revenue by month or day as chart points; other groupings have no chart (ADR-164). */
function revenueChart(
  r: { groupBy: string; rows: { key: string | null; revenueHt: string }[] },
  t: TFunction,
): { title: string; points: ChartPoint[] } | null {
  if ((r.groupBy !== 'month' && r.groupBy !== 'day') || r.rows.length === 0) {
    return null
  }
  return {
    title: t('reports.revenueChart'),
    points: r.rows.map((x) => ({ label: dateKey(x.key), value: x.revenueHt, display: formatMoney(x.revenueHt) })),
  }
}

const pad = (n: number): Cell[] => Array.from({ length: n }, () => blank)

export const REPORTS: readonly ReportDefinition[] = [
  {
    key: 'revenue',
    path: 'revenue',
    permission: 'report.sales',
    filters: ['period'],
    groups: ['month', 'day', 'category', 'product'],
    chart: (data, t) => revenueChart(data as RevenueReport, t),
    sections(data, t) {
      const r = data as RevenueReport
      const lead = groupColumns(r.groupBy, t).length
      return [
        {
          columns: [...groupColumns(r.groupBy, t), col(t('reports.col.revenueHt'), true)],
          rows: r.rows.map((x) => [...groupCells(r.groupBy, x, t), money(x.revenueHt)]),
          totals: [
            [text(t('reports.total')), ...pad(lead - 1), money(r.totalHt)],
            [text(t('reports.uninvoiced', { count: r.uninvoicedDeliveryNotes.count })), ...pad(lead - 1), money(r.uninvoicedDeliveryNotes.totalHt)],
          ],
        },
      ]
    },
  },
  {
    key: 'margin',
    path: 'margin',
    permission: 'report.margin',
    filters: ['period'],
    groups: ['category', 'product'],
    sections(data, t) {
      const r = data as MarginReport
      const product = r.groupBy === 'product'
      return [
        {
          columns: [
            ...groupColumns(r.groupBy, t),
            ...(product ? [col(t('reports.col.quantity'), true)] : []),
            col(t('reports.col.revenueHt'), true),
            col(t('reports.col.cost'), true),
            col(t('reports.col.margin'), true),
            col(t('reports.col.marginRate'), true),
          ],
          rows: r.rows.map((x) => [
            ...groupCells(r.groupBy, x, t),
            ...(product ? [quantity(x.quantityBase)] : []),
            money(x.revenueHt),
            money(x.cost),
            money(x.margin),
            percent(x.marginRate),
          ]),
          totals: [
            [text(t('reports.total')), ...pad(product ? 2 : 0), money(r.totalRevenueHt), money(r.totalCost), money(r.totalMargin), percent(r.marginRate)],
          ],
        },
      ]
    },
  },
  {
    key: 'salesStatistics',
    path: 'sales-statistics',
    permission: 'report.sales',
    filters: ['period', 'customerType'],
    groups: ['month', 'day', 'customer', 'wilaya', 'commune'],
    chart: (data, t) => revenueChart(data as StatisticsReport, t),
    sections(data, t) {
      const r = data as StatisticsReport
      const lead: Column[] =
        r.groupBy === 'customer'
          ? [col(t('reports.col.customer'))]
          : r.groupBy === 'wilaya'
            ? [col(t('reports.col.wilaya'))]
            : r.groupBy === 'commune'
              ? [col(t('reports.col.wilaya')), col(t('reports.col.commune'))]
              : groupColumns(r.groupBy, t)
      const cells = (x: StatisticsReport['rows'][number]): Cell[] =>
        r.groupBy === 'customer'
          ? [text(x.key === null ? t('reports.counterSale') : x.label)]
          : r.groupBy === 'wilaya'
            ? [text(x.label ?? '—')]
            : r.groupBy === 'commune'
              ? [text(x.code ?? '—'), text(x.label ?? '—')]
              : groupCells(r.groupBy, x, t)
      return [
        {
          columns: [...lead, col(t('reports.col.documents'), true), col(t('reports.col.revenueHt'), true)],
          rows: r.rows.map((x) => [...cells(x), count(x.documents), money(x.revenueHt)]),
          totals: [[text(t('reports.total')), ...pad(lead.length - 1), count(r.totalDocuments), money(r.totalHt)]],
        },
      ]
    },
  },
  {
    key: 'stockValuation',
    path: 'stock-valuation',
    permission: 'report.stock',
    filters: ['basis'],
    groups: ['product', 'category'],
    sections(data, t) {
      const r = data as ValuationReport
      if (r.groupBy === 'product') {
        return [
          {
            columns: [
              col(t('reports.col.code')),
              col(t('reports.col.product')),
              col(t('reports.col.quantity'), true),
              col(t('reports.col.value'), true),
              col(t('reports.col.unsellableQuantity'), true),
              col(t('reports.col.unsellableValue'), true),
            ],
            rows: r.rows.map((x) => [
              text(x.code),
              text(x.label),
              { text: `${x.quantityBase === null ? '' : formatQuantity(x.quantityBase)} ${x.baseUnit ?? ''}`.trim(), num: true },
              money(x.value),
              quantity(x.unsellableQuantityBase),
              money(x.unsellableValue),
            ]),
            totals: [[text(t('reports.total')), blank, blank, money(r.totalValue), blank, money(r.totalUnsellableValue)]],
          },
        ]
      }
      return [
        {
          columns: [col(t('reports.col.category')), col(t('reports.col.value'), true), col(t('reports.col.unsellableValue'), true)],
          rows: r.rows.map((x) => [text(x.label ?? t('reports.noCategory')), money(x.value), money(x.unsellableValue)]),
          totals: [[text(t('reports.total')), money(r.totalValue), money(r.totalUnsellableValue)]],
        },
      ]
    },
  },
  {
    key: 'customerBalances',
    path: 'customer-balances',
    permission: 'report.finance',
    filters: ['customerType'],
    sections(data, t) {
      const r = data as CustomerBalancesReport
      const buckets: Column[] = []
      let previous = 0
      for (const limit of r.bucketDays) {
        buckets.push(col(t('reports.col.bucket', { from: previous + 1, to: limit }), true))
        previous = limit
      }
      buckets.push(col(t('reports.col.bucketOver', { days: previous }), true))
      const ageing = (a: CustomerBalancesReport['totals']): Cell[] => [
        money(a.notDue),
        ...a.overdue.map(money),
        money(a.advance),
        money(a.balance),
        money(a.uninvoicedDeliveryNotes),
      ]
      return [
        {
          columns: [
            col(t('reports.col.customer')),
            col(t('reports.col.phone')),
            col(t('reports.col.notDue'), true),
            ...buckets,
            col(t('reports.col.advance'), true),
            col(t('reports.col.balance'), true),
            col(t('reports.col.uninvoicedNotes'), true),
          ],
          rows: r.rows.map((x) => [text(x.name), text(x.phone ?? ''), ...ageing(x)]),
          totals: [[text(t('reports.total')), blank, ...ageing(r.totals)]],
        },
      ]
    },
  },
  {
    key: 'supplierPayables',
    path: 'supplier-payables',
    permission: 'report.finance',
    filters: ['supplierId'],
    sections(data, t) {
      const r = data as PayablesReport
      const summary = (label: string, value: string): Cell[] => [text(label), ...pad(5), money(value)]
      return [
        {
          columns: [
            col(t('reports.col.supplier')),
            col(t('reports.col.invoice')),
            col(t('reports.col.invoiceDate')),
            col(t('reports.col.dueDate')),
            col(t('reports.col.daysOverdue'), true),
            col(t('reports.col.totalTtc'), true),
            col(t('reports.col.open'), true),
          ],
          rows: r.rows.map((x) => [
            text(x.supplierName),
            text(x.invoiceNumber),
            text(formatDate(x.invoiceDate)),
            text(formatDate(x.dueDate)),
            count(x.daysOverdue),
            money(x.totalTtc),
            money(x.open),
          ]),
          totals: [
            summary(t('reports.total'), r.totalOpen),
            summary(t('reports.overdueTotal'), r.totalOverdue),
            summary(t('reports.unappliedCredits'), r.unappliedCreditNotes),
            summary(t('reports.advances'), r.advances),
            summary(t('reports.balance'), r.balance),
          ],
        },
      ]
    },
  },
  {
    key: 'cashSessions',
    path: 'cash-sessions',
    permission: 'report.finance',
    filters: ['period', 'cashierId'],
    sections(data, t) {
      const r = data as CashSessionsReport
      const summary = (label: string, value: string): Cell[] => [text(label), ...pad(7), money(value)]
      return [
        {
          columns: [
            col(t('reports.col.session'), true),
            col(t('reports.col.cashier')),
            col(t('reports.col.openedAt')),
            col(t('reports.col.closedAt')),
            col(t('reports.col.closedBy')),
            col(t('reports.col.opening'), true),
            col(t('reports.col.expected'), true),
            col(t('reports.col.counted'), true),
            col(t('reports.col.variance'), true),
          ],
          rows: r.rows.map((x) => [
            count(x.sessionId),
            text(x.cashierName),
            text(formatDateTime(x.openedAt)),
            text(formatDateTime(x.closedAt)),
            text(x.closedByName ?? '—'),
            money(x.openingAmount),
            money(x.expectedAmount),
            money(x.countedAmount),
            money(x.variance),
          ]),
          totals: [
            summary(t('reports.shortages'), r.totalShortage),
            summary(t('reports.surpluses'), r.totalSurplus),
            summary(t('reports.netVariance'), r.totalVariance),
          ],
        },
      ]
    },
  },
  {
    key: 'lotTraceability',
    path: 'lot-traceability',
    permission: 'lot.recall',
    filters: ['lotId'],
    required: ['lotId'],
    sections(data, t) {
      const r = data as LotTraceability
      const unit = r.lot.product.baseUnit
      return [
        {
          heading: t('reports.trace.lot'),
          columns: [
            col(t('reports.col.lot')),
            col(t('reports.col.product')),
            col(t('reports.col.expiry')),
            col(t('reports.col.status')),
            col(t('reports.col.stock'), true),
          ],
          rows: [
            [
              text(r.lot.lotNumber),
              text(`${r.lot.product.sku} — ${r.lot.product.name}`),
              text(formatDate(r.lot.expiryDate)),
              text(t(`reports.lotStatus.${r.lot.status}`) + (r.lot.expired ? ` (${t('reports.expired')})` : '')),
              { text: `${formatQuantity(r.lot.quantityOnHand)} ${unit}`, num: true },
            ],
          ],
          totals: [],
        },
        {
          heading: t('reports.trace.movements'),
          columns: [
            col(t('reports.col.date')),
            col(t('reports.col.movement')),
            col(t('reports.col.quantity'), true),
            col(t('reports.col.document')),
            col(t('reports.col.party')),
            col(t('reports.col.user')),
          ],
          rows: r.movements.map((m) => [
            text(formatDateTime(m.at)),
            text(t(`reports.movement.${m.type}`, { defaultValue: m.type })),
            quantity(m.quantity),
            text(m.document ? `${t(`reports.document.${m.document.type}`, { defaultValue: m.document.type })} ${m.document.number}` : ''),
            text(m.party?.name ?? (m.document && m.party === null && m.type.startsWith('sale') ? t('reports.counterSale') : '')),
            text(m.user),
          ]),
          totals: [],
        },
        {
          heading: t('reports.trace.suppliers'),
          columns: [col(t('reports.col.supplier')), col(t('reports.col.received'), true), col(t('reports.col.returned'), true)],
          rows: r.suppliers.map((s) => [text(s.name), quantity(s.received), quantity(s.returned)]),
          totals: [],
        },
        {
          heading: t('reports.trace.customers'),
          columns: [
            col(t('reports.col.customer')),
            col(t('reports.col.sold'), true),
            col(t('reports.col.returned'), true),
            col(t('reports.col.net'), true),
          ],
          rows: r.customers.map((c) => [
            text(c.id === null ? t('reports.counterSale') : c.name),
            quantity(c.sold),
            quantity(c.returned),
            quantity(c.net),
          ]),
          totals: [],
        },
      ]
    },
  },
]

export function reportByKey(key: string | undefined): ReportDefinition | undefined {
  return REPORTS.find((r) => r.key === key)
}
