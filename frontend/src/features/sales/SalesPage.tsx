import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { SalesTable } from './SalesTable'
import { useSales } from './saleApi'

const TYPES = ['quote', 'delivery_note', 'invoice', 'receipt'] as const
const STATUSES = ['draft', 'confirmed', 'cancelled'] as const

/** `/sales` (frontend.md §4.8): the documents newest first, by type, status and exact number; drafts included. */
export function SalesPage() {
  const { t } = useTranslation()
  const [params, setParams] = useSearchParams()
  const docType = params.get('docType') ?? ''
  const status = params.get('status') ?? ''
  const number = params.get('number') ?? ''
  const page = Number(params.get('page') ?? '0')
  const sales = useSales({ docType, status, number, page })
  const update = (next: Record<string, string>) =>
    setParams(Object.fromEntries(Object.entries({ docType, status, number, ...next }).filter(([, v]) => v !== '')), { replace: true })
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{t('sales.title')}</h1>
      <div className="flex flex-wrap gap-4">
        <div className="w-48">
          <SelectField label={t('sales.type')} value={docType} onChange={(e) => update({ docType: e.target.value, page: '' })} options={[{ value: '', label: t('admin.all') }, ...TYPES.map((type) => ({ value: type, label: t(`pos.types.${type}`) }))]} />
        </div>
        <div className="w-48">
          <SelectField label={t('sales.status')} value={status} onChange={(e) => update({ status: e.target.value, page: '' })} options={[{ value: '', label: t('admin.all') }, ...STATUSES.map((s) => ({ value: s, label: t(`sales.statuses.${s}`) }))]} />
        </div>
        <div className="w-64">
          <TextField label={t('sales.number')} type="search" value={number} onChange={(e) => update({ number: e.target.value, page: '' })} />
        </div>
      </div>
      {sales.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : sales.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(sales.error.problem, t)}</Alert>
      ) : !sales.data || sales.data.content.length === 0 ? (
        <p className="text-mute">{t('sales.empty')}</p>
      ) : (
        <>
          <SalesTable sales={sales.data.content} />
          <Pager page={sales.data.page} onPage={(next) => update({ page: String(next) })} />
        </>
      )}
    </section>
  )
}
