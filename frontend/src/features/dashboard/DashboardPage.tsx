import { useTranslation } from 'react-i18next'
import { hasPermission, useCurrentUser } from '../auth'
import { ExpiryWidget, LowStockWidget } from '../inventory'

/**
 * `/` (frontend.md §3.1): one widget per module the user can read; expiry alerts from Phase 3 (W12), low stock from
 * Phase 4 (ADR-089).
 */
export function DashboardPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const lots = hasPermission(current, 'lot.read')
  const stock = hasPermission(current, 'stock.read')
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{t('dashboard.title')}</h1>
      {lots && <ExpiryWidget />}
      {stock && <LowStockWidget />}
      {!lots && !stock && <p className="text-mute">{t('dashboard.empty')}</p>}
    </section>
  )
}
