import { useTranslation } from 'react-i18next'
import { hasPermission, useCurrentUser } from '../auth'
import { ExpiryWidget } from '../inventory/ExpiryWidget'
import { LowStockWidget } from '../inventory/LowStockWidget'
import { ReorderWidget } from '../inventory/ReorderWidget'
import { DebtWidget } from './DebtWidget'
import { SalesWidget } from './SalesWidget'

/**
 * `/` (frontend.md §3.1): one widget per module the user can read; expiry alerts from Phase 3 (W12), low stock and
 * pending reorder proposals from Phase 4 (ADR-089, W10), the sales charts (`report.sales`) and the debts
 * (`report.finance`) from Phase 12 (ADR-176).
 */
export function DashboardPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const lots = hasPermission(current, 'lot.read')
  const stock = hasPermission(current, 'stock.read')
  const reorder = hasPermission(current, 'reorder.read')
  const sales = hasPermission(current, 'report.sales')
  const debts = hasPermission(current, 'report.finance')
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{t('dashboard.title')}</h1>
      {sales && <SalesWidget />}
      {debts && <DebtWidget />}
      {lots && <ExpiryWidget />}
      {stock && <LowStockWidget />}
      {reorder && <ReorderWidget />}
      {!lots && !stock && !reorder && !sales && !debts && <p className="text-mute">{t('dashboard.empty')}</p>}
    </section>
  )
}
