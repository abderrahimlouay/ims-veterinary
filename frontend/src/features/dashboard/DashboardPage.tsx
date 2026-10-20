import { useTranslation } from 'react-i18next'

/** Placeholder until the dashboard widgets arrive (expiry alerts in Phase 3, cash session in Phase 7). */
export function DashboardPage() {
  const { t } = useTranslation()
  return (
    <section>
      <h1 className="text-2xl font-semibold mb-2">{t('dashboard.title')}</h1>
      <p className="text-mute">{t('dashboard.empty')}</p>
    </section>
  )
}
