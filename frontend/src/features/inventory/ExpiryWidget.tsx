import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { problemMessage } from '../../lib/errors'
import { AlertBadge } from './LotBadges'
import { ALERT_LEVELS, useExpiryAlerts, type AlertLevel, type ExpiryAlerts } from './lotApi'

/** The lot list for a level: expired lots, or lots with stock expiring within that level's threshold. */
function listLink(level: AlertLevel, thresholds: ExpiryAlerts['thresholds']): string {
  return level === 'expired' ? '/lots?status=expired&hasStock=true' : `/lots?expiringWithinDays=${thresholds[level]}&hasStock=true`
}

/**
 * Dashboard widget (frontend.md §4.2, W12): lots with stock per alert level, from the server's levels and the
 * configured thresholds (ADR-056); each count links to the lot list.
 */
export function ExpiryWidget() {
  const { t } = useTranslation()
  const alerts = useExpiryAlerts(true)
  return (
    <section className="flex flex-col gap-3 rounded-ctl border border-line bg-surface p-4 max-w-xl">
      <h2 className="font-semibold">{t('lots.widget.title')}</h2>
      {alerts.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : alerts.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(alerts.error.problem, t)}</Alert>
      ) : alerts.data && ALERT_LEVELS.every((level) => alerts.data[level].length === 0) ? (
        <p className="text-mute">{t('lots.widget.none')}</p>
      ) : (
        alerts.data && (
          <ul className="grid grid-cols-2 gap-2">
            {ALERT_LEVELS.map((level) => (
              <li key={level}>
                <Link
                  to={listLink(level, alerts.data.thresholds)}
                  className="flex items-center justify-between gap-2 rounded-ctl border border-line px-3 py-2 hover:border-control focus:outline-2 focus:outline-brand"
                >
                  <AlertBadge level={level} />
                  <span className="text-lg font-semibold">{alerts.data[level].length}</span>
                </Link>
              </li>
            ))}
          </ul>
        )
      )}
    </section>
  )
}
