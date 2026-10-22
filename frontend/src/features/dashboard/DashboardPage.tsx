import { useTranslation } from 'react-i18next'
import { hasPermission, useCurrentUser } from '../auth'
import { ExpiryWidget } from '../inventory'

/** `/` (frontend.md §3.1): one widget per module the user can read; expiry alerts from Phase 3 (W12). */
export function DashboardPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const lots = hasPermission(current, 'lot.read')
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{t('dashboard.title')}</h1>
      {lots ? <ExpiryWidget /> : <p className="text-mute">{t('dashboard.empty')}</p>}
    </section>
  )
}
