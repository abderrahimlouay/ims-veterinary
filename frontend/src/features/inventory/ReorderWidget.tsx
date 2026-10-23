import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { problemMessage } from '../../lib/errors'
import { useProposals } from './reorderApi'

/** Dashboard widget (frontend.md §3.1): how many reorder proposals are pending, opening the list. */
export function ReorderWidget() {
  const { t } = useTranslation()
  const pending = useProposals('pending', 0, 1)
  return (
    <section className="flex flex-col gap-3 rounded-ctl border border-line bg-surface p-4 max-w-xl">
      <h2 className="font-semibold">{t('reorder.widget.title')}</h2>
      {pending.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : pending.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(pending.error.problem, t)}</Alert>
      ) : (
        <Link to="/reorder" className="text-brand underline underline-offset-4">
          {t('reorder.widget.pending', { count: pending.data?.page.totalElements ?? 0 })}
        </Link>
      )}
    </section>
  )
}
