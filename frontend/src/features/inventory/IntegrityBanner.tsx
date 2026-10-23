import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { formatDateTime } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { useIntegrityRun } from './stockApi'

/**
 * Shown to `audit.read` holders while the latest integrity run found lots whose balance differs from their ledger;
 * it disappears after a clean run (ADR-086). Other users never see it nor load the run.
 */
export function IntegrityBanner() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const allowed = hasPermission(current, 'audit.read')
  const run = useIntegrityRun(allowed)
  if (!allowed || !run.data || run.data.mismatchCount === 0) {
    return null
  }
  return (
    <div role="alert" className="px-4 py-2 border-b border-danger/40 bg-danger/10 text-danger text-sm">
      {t('stock.integrity.banner', { count: run.data.mismatchCount, at: formatDateTime(run.data.startedAt) })}{' '}
      <Link to="/stock/integrity" className="underline font-medium">
        {t('stock.integrity.open')}
      </Link>
    </div>
  )
}
