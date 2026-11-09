import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { BarListChart } from '../../components/BarListChart'
import { ColumnChart, type ChartPoint } from '../../components/ColumnChart'
import { problemMessage } from '../../lib/errors'
import { businessToday } from '../../lib/expiry'
import { formatMoney } from '../../lib/format'
import { useReport, type RevenueReport } from '../reports/api'
import { dateKey } from '../reports/definitions'
import { addDays, daysBetween, firstOfMonth, monthsBetween } from './periods'

const DAYS = 30
const MONTHS = 12
const TOP = 10

/** A day as `JJ/MM` under a column (the axis has 30 of them); a month as the reports show it. */
function axisLabel(period: string): string {
  return period.length === 10 ? `${period.substring(8, 10)}/${period.substring(5, 7)}` : dateKey(period)
}

/** Every period of the chart, a period without sales at zero (the report lists only the periods with sales). */
function filled(periods: string[], rows: RevenueReport['rows']): ChartPoint[] {
  const byKey = new Map(rows.map((row) => [row.key, row.revenueHt]))
  return periods.map((period) => {
    const value = byKey.get(period) ?? '0.00'
    return { label: axisLabel(period), value, display: formatMoney(value) }
  })
}

function Loading({ query }: { query: { isPending: boolean; error: unknown } }) {
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

/**
 * Dashboard sales (ADR-176): revenue excl. VAT per day over the last 30 days and per month over the last 12, and this
 * month's 10 best products and its categories; from the revenue report (`report.sales`), net of credit notes as it is.
 */
export function SalesWidget() {
  const { t } = useTranslation()
  const today = businessToday()
  const dayFrom = addDays(today, 1 - DAYS)
  const monthFrom = firstOfMonth(today, 1 - MONTHS)
  const daily = useReport<RevenueReport>('revenue', { from: dayFrom, to: today, groupBy: 'day' })
  const monthly = useReport<RevenueReport>('revenue', { from: monthFrom, to: today, groupBy: 'month' })
  const products = useReport<RevenueReport>('revenue', { groupBy: 'product' })
  const categories = useReport<RevenueReport>('revenue', { groupBy: 'category' })

  return (
    <section className="flex flex-col gap-4 rounded-ctl border border-line bg-surface p-4" aria-labelledby="dashboard-sales">
      <div className="flex flex-wrap items-baseline gap-3">
        <h2 id="dashboard-sales" className="font-semibold">
          {t('dashboard.sales.title')}
        </h2>
        <span className="text-xs text-mute">{t('dashboard.sales.excludingTax')}</span>
        <Link to="/reports" className="ms-auto text-sm text-brand underline underline-offset-4">
          {t('dashboard.sales.reports')}
        </Link>
      </div>
      <div className="grid gap-6 xl:grid-cols-2">
        <div className="flex flex-col gap-1">
          <Loading query={daily} />
          {daily.data && (
            <>
              <p className="text-sm">
                {t('dashboard.sales.periodTotal', { days: DAYS })}{' '}
                <span className="font-semibold" data-testid="sales-30-days">
                  {formatMoney(daily.data.totalHt)}
                </span>
              </p>
              <ColumnChart title={t('dashboard.sales.perDay', { days: DAYS })} points={filled(daysBetween(dayFrom, today), daily.data.rows)} />
            </>
          )}
        </div>
        <div className="flex flex-col gap-1">
          <Loading query={monthly} />
          {monthly.data && (
            <>
              <p className="text-sm">
                {t('dashboard.sales.periodTotalMonths', { months: MONTHS })}{' '}
                <span className="font-semibold">{formatMoney(monthly.data.totalHt)}</span>
              </p>
              <ColumnChart
                title={t('dashboard.sales.perMonth', { months: MONTHS })}
                points={filled(monthsBetween(monthFrom, today), monthly.data.rows)}
              />
            </>
          )}
        </div>
        <div>
          <Loading query={products} />
          {products.data &&
            (products.data.rows.length === 0 ? (
              <p className="text-sm text-mute">{t('dashboard.sales.noSalesThisMonth')}</p>
            ) : (
              <BarListChart
                title={t('dashboard.sales.topProducts', { count: TOP })}
                items={products.data.rows.slice(0, TOP).map((row) => ({
                  label: row.label ?? '—',
                  value: row.revenueHt,
                  display: formatMoney(row.revenueHt),
                  to: row.key === null ? undefined : `/products/${row.key}`,
                }))}
              />
            ))}
        </div>
        <div>
          <Loading query={categories} />
          {categories.data && categories.data.rows.length > 0 && (
            <BarListChart
              title={t('dashboard.sales.byCategory')}
              items={categories.data.rows.map((row) => ({
                label: row.label ?? t('reports.noCategory'),
                value: row.revenueHt,
                display: formatMoney(row.revenueHt),
              }))}
            />
          )}
        </div>
      </div>
    </section>
  )
}
