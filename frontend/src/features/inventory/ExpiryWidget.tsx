import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { problemMessage } from '../../lib/errors'
import { AlertBadge } from './LotBadges'
import { ALERT_LEVELS, useExpiryAlerts, type AlertLevel, type ExpiryAlerts } from './lotApi'

/** The lot list of exactly that level, with stock: the same lots as the count (any stored status). */
function listLink(level: AlertLevel): string {
  return `/lots?alertLevel=${level}&hasStock=true`
}

/** "Expired", or the level with its threshold from the settings, e.g. "Critical (≤ 15 days)". */
function tileLabel(level: AlertLevel, thresholds: ExpiryAlerts['thresholds'], t: TFunction): string {
  return level === 'expired' ? t('lots.widget.tiles.expired') : t(`lots.widget.tiles.${level}`, { days: thresholds[level] })
}

/**
 * Dashboard widget (frontend.md §4.2, W12): lots with stock per alert level, from the server's levels and the
 * configured thresholds (ADR-056); each count links to the lot list of exactly that level, so both match.
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
                  to={listLink(level)}
                  className="flex items-center justify-between gap-2 rounded-ctl border border-line px-3 py-2 hover:border-control focus:outline-2 focus:outline-brand"
                >
                  <AlertBadge level={level} label={tileLabel(level, alerts.data.thresholds, t)} />{' '}
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
