import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { problemMessage } from '../../lib/errors'
import { StockStatusBadge } from './StockBadges'
import { useStockLevels, type StockStatus } from './stockApi'

const STATUSES: readonly Exclude<StockStatus, 'in_stock'>[] = ['out', 'low']

/**
 * Dashboard widget (ADR-089): how many products are out of stock or low (ADR-080, server statuses), each count
 * opening the stock list filtered the same way, so both match.
 */
export function LowStockWidget() {
  const { t } = useTranslation()
  const out = useStockLevels({ q: '', status: 'out', belowReorder: false, page: 0 }, 1)
  const low = useStockLevels({ q: '', status: 'low', belowReorder: false, page: 0 }, 1)
  const queries = { out, low }
  const failed = [out, low].find((q) => q.error instanceof ApiError)?.error
  return (
    <section className="flex flex-col gap-3 rounded-ctl border border-line bg-surface p-4 max-w-xl">
      <h2 className="font-semibold">{t('stock.widget.title')}</h2>
      {failed instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(failed.problem, t)}</Alert>
      ) : out.isPending || low.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : out.data?.page.totalElements === 0 && low.data?.page.totalElements === 0 ? (
        <p className="text-mute">{t('stock.widget.none')}</p>
      ) : (
        <ul className="grid grid-cols-2 gap-2">
          {STATUSES.map((status) => (
            <li key={status}>
              <Link
                to={`/stock?status=${status}`}
                className="flex items-center justify-between gap-2 rounded-ctl border border-line px-3 py-2 hover:border-control focus:outline-2 focus:outline-brand"
              >
                <StockStatusBadge status={status} />{' '}
                <span className="text-lg font-semibold">{queries[status].data?.page.totalElements ?? 0}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
