import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { BarListChart, type BarItem } from '../../components/BarListChart'
import { ColumnChart, type ChartPoint } from '../../components/ColumnChart'
import type { ChartTone } from '../../components/chartColours'
import { sumDecimals } from '../../lib/decimal'
import { problemMessage } from '../../lib/errors'
import { formatMoney } from '../../lib/format'
import { useReport, type CustomerBalancesReport, type PayablesReport } from '../reports/api'

const TOP = 5
/** Not due, then later and later (ADR-177): graded, the label under each column naming it. */
const LATENESS: ChartTone[] = ['status-good', 'status-warning', 'status-serious', 'status-critical']

function Status({ query }: { query: { isPending: boolean; error: unknown } }) {
  const { t } = useTranslation()
  if (query.error instanceof ApiError) {
    return <Alert tone="danger">{problemMessage(query.error.problem, t)}</Alert>
  }
  return query.isPending ? (
    <p role="status" className="text-mute">
      {t('app.loading')}
    </p>
  ) : null
}

/** The ageing columns of the customer balances (ADR-148): not due, then one per bucket of the setting `ageing_buckets`. */
function ageing(report: CustomerBalancesReport, t: (key: string, options?: Record<string, unknown>) => string): ChartPoint[] {
  const labels = [t('dashboard.debt.notDue')]
  let start = 1
  for (const end of report.bucketDays) {
    labels.push(t('dashboard.debt.bucket', { from: start, to: end }))
    start = end + 1
  }
  labels.push(t('dashboard.debt.beyond', { days: start - 1 }))
  return [report.totals.notDue, ...report.totals.overdue].map((value, i) => ({
    label: labels[i] ?? '',
    value,
    display: formatMoney(value),
    tone: LATENESS[Math.min(i, LATENESS.length - 1)],
  }))
}

/** The supplier credit notes not yet applied and the advances paid, which the balance deducts (BR-PAY-014). */
function deducted(report: PayablesReport): string | null {
  const total = sumDecimals([report.unappliedCreditNotes, report.advances], 2)
  return total !== null && Number(total) > 0 ? total : null
}

/** The suppliers owed most: their open invoices summed (the payables report lists invoices). */
function topSuppliers(report: PayablesReport): BarItem[] {
  const bySupplier = new Map<number, { name: string; amounts: string[] }>()
  for (const row of report.rows) {
    const entry = bySupplier.get(row.supplierId) ?? { name: row.supplierName, amounts: [] }
    entry.amounts.push(row.open)
    bySupplier.set(row.supplierId, entry)
  }
  return [...bySupplier.entries()]
    .map(([id, entry]) => {
      const value = sumDecimals(entry.amounts, 2) ?? '0.00'
      return { label: entry.name, value, display: formatMoney(value), to: `/suppliers/${id}` }
    })
    .sort((a, b) => Number(b.value) - Number(a.value))
    .slice(0, TOP)
}

/**
 * Dashboard debts (ADR-176), as of today, from the finance reports (`report.finance`): what customers owe the shop, by
 * lateness, with the five who owe most; what the shop owes its suppliers, its overdue part and the five owed most.
 */
export function DebtWidget() {
  const { t } = useTranslation()
  const customers = useReport<CustomerBalancesReport>('customer-balances', {})
  const suppliers = useReport<PayablesReport>('supplier-payables', {})

  return (
    <section className="flex flex-col gap-4 rounded-ctl border border-line bg-surface p-4" aria-labelledby="dashboard-debt">
      <h2 id="dashboard-debt" className="font-semibold">
        {t('dashboard.debt.title')}
      </h2>
      <div className="grid gap-6 xl:grid-cols-2">
        <div className="flex flex-col gap-3">
          <h3 className="text-sm font-medium text-mute">{t('dashboard.debt.customers')}</h3>
          <Status query={customers} />
          {customers.data && (
            <>
              <p className="text-2xl font-semibold tabular-nums" data-testid="debt-customers-total">
                {formatMoney(customers.data.totals.balance)}
              </p>
              {Number(customers.data.totals.advance) > 0 && (
                <p className="text-xs text-mute">{t('dashboard.debt.advances', { amount: formatMoney(customers.data.totals.advance) })}</p>
              )}
              <ColumnChart title={t('dashboard.debt.byLateness')} points={ageing(customers.data, t)} />
              {customers.data.rows.some((row) => Number(row.balance) > 0) && (
                <BarListChart
                  tone="chart-7"
                  title={t('dashboard.debt.topCustomers', { count: TOP })}
                  items={customers.data.rows
                    .filter((row) => Number(row.balance) > 0)
                    .slice(0, TOP)
                    .map((row) => ({
                      label: row.name,
                      value: row.balance,
                      display: formatMoney(row.balance),
                      to: `/customers/${row.customerId}`,
                    }))}
                />
              )}
              <Link to="/reports/customerBalances" className="text-sm text-brand underline underline-offset-4">
                {t('dashboard.debt.customersReport')}
              </Link>
            </>
          )}
        </div>
        <div className="flex flex-col gap-3">
          <h3 className="text-sm font-medium text-mute">{t('dashboard.debt.suppliers')}</h3>
          <Status query={suppliers} />
          {suppliers.data && (
            <>
              <p className="text-2xl font-semibold tabular-nums" data-testid="debt-suppliers-total">
                {formatMoney(suppliers.data.balance)}
              </p>
              <p className="text-sm">
                {t('dashboard.debt.openInvoices', { amount: formatMoney(suppliers.data.totalOpen) })}
                {' · '}
                <span className={Number(suppliers.data.totalOverdue) > 0 ? 'text-danger' : 'text-mute'}>
                  {t('dashboard.debt.overdue', { amount: formatMoney(suppliers.data.totalOverdue) })}
                </span>
              </p>
              {deducted(suppliers.data) !== null && (
                <p className="text-xs text-mute">{t('dashboard.debt.deducted', { amount: formatMoney(deducted(suppliers.data) ?? '0') })}</p>
              )}
              {suppliers.data.rows.length > 0 && (
                <BarListChart tone="chart-5" title={t('dashboard.debt.topSuppliers', { count: TOP })} items={topSuppliers(suppliers.data)} />
              )}
              <Link to="/reports/supplierPayables" className="text-sm text-brand underline underline-offset-4">
                {t('dashboard.debt.suppliersReport')}
              </Link>
            </>
          )}
        </div>
      </div>
    </section>
  )
}
