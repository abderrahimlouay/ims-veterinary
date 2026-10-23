import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { problemMessage } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { CountStatusBadge } from './CountBadges'
import { COUNT_STATUSES, useCounts } from './countApi'

/** `/inventory-counts` (frontend.md §4.4): counts, newest first, by status; a new count for `inventory.count` holders. */
export function CountsPage() {
  const { t } = useTranslation()
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const page = Number(params.get('page') ?? '0')
  const counts = useCounts(status, page)
  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{t('counts.title')}</h1>
        <Link
          to="/inventory-counts/new"
          className="ms-auto inline-flex items-center h-10 px-4 rounded-ctl bg-brand text-brand-ink text-sm font-medium hover:bg-brand/90 focus:outline-2 focus:outline-offset-2 focus:outline-brand"
        >
          {t('counts.new')}
        </Link>
      </div>
      <div className="max-w-xs">
        <SelectField
          label={t('counts.statusFilter')}
          value={status}
          onChange={(e) => setParams(e.target.value === '' ? {} : { status: e.target.value }, { replace: true })}
          options={[{ value: '', label: t('admin.all') }, ...COUNT_STATUSES.map((s) => ({ value: s, label: t(`counts.status.${s}`) }))]}
        />
      </div>
      {counts.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : counts.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(counts.error.problem, t)}</Alert>
      ) : !counts.data || counts.data.content.length === 0 ? (
        <p className="text-mute">{t('counts.empty')}</p>
      ) : (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('counts.number')}</th>
                <th className="text-start">{t('counts.type')}</th>
                <th className="text-start">{t('counts.statusFilter')}</th>
                <th className="text-start">{t('counts.startedAt')}</th>
                <th className="text-start">{t('counts.startedBy')}</th>
                <th className="text-end">{t('counts.progress')}</th>
              </tr>
            </thead>
            <tbody>
              {counts.data.content.map((c) => (
                <tr key={c.id} className="border-b border-line">
                  <td className="py-2">
                    <Link to={`/inventory-counts/${c.id}`} className="text-brand underline underline-offset-4">
                      {t('counts.label', { id: c.id })}
                    </Link>
                  </td>
                  <td>{t(`counts.types.${c.type}`)}</td>
                  <td>
                    <CountStatusBadge status={c.status} />
                  </td>
                  <td>{formatDateTime(c.startedAt)}</td>
                  <td>{c.startedBy.fullName}</td>
                  <td className="text-end">{t('counts.counted', { counted: c.countedCount, lines: c.lineCount })}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={counts.data.page} onPage={(p) => setParams({ ...(status === '' ? {} : { status }), page: String(p) }, { replace: true })} />
        </>
      )}
    </section>
  )
}
