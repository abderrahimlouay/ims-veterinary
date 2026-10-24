import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { problemMessage } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { useReceipts } from './receiptApi'
import { useSuppliers } from './supplierApi'

/** `/goods-receipts` (frontend.md §4.5): receipts newest first, by status and supplier; a free receipt. */
export function ReceiptsPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const supplierId = params.get('supplierId') ?? ''
  const page = Number(params.get('page') ?? '0')
  const receipts = useReceipts(status, supplierId, page)
  const suppliers = useSuppliers('', '', 0, 100)
  const update = (next: Record<string, string>) =>
    setParams(Object.fromEntries(Object.entries({ status, supplierId, ...next }).filter(([, v]) => v !== '')), { replace: true })

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{t('purchasing.receipts.title')}</h1>
        {hasPermission(current, 'receipt.create') && (
          <Link to="/goods-receipts/new" className="ms-auto h-10 px-4 inline-flex items-center rounded-ctl bg-brand text-brand-ink text-sm font-medium">
            {t('purchasing.receipts.newFree')}
          </Link>
        )}
      </div>
      <div className="flex flex-wrap gap-4">
        <div className="w-48">
          <SelectField
            label={t('purchasing.orders.status')}
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}
            options={[{ value: '', label: t('admin.all') }, ...(['confirmed', 'cancelled'] as const).map((s) => ({ value: s, label: t(`purchasing.receipts.statuses.${s}`) }))]}
          />
        </div>
        <div className="w-64">
          <SelectField
            label={t('purchasing.orders.supplier')}
            value={supplierId}
            onChange={(e) => update({ supplierId: e.target.value, page: '' })}
            options={[{ value: '', label: t('admin.all') }, ...(suppliers.data?.content ?? []).map((s) => ({ value: String(s.id), label: s.name }))]}
          />
        </div>
      </div>
      {receipts.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : receipts.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(receipts.error.problem, t)}</Alert>
      ) : !receipts.data || receipts.data.content.length === 0 ? (
        <p className="text-mute">{t('purchasing.receipts.empty')}</p>
      ) : (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('purchasing.orders.number')}</th>
                <th className="text-start">{t('purchasing.orders.supplier')}</th>
                <th className="text-start">{t('purchasing.receipts.order')}</th>
                <th className="text-start">{t('purchasing.receipts.receivedAt')}</th>
                <th className="text-start">{t('purchasing.orders.status')}</th>
              </tr>
            </thead>
            <tbody>
              {receipts.data.content.map((r) => (
                <tr key={r.id} className="border-b border-line">
                  <td className="py-2">
                    <Link to={`/goods-receipts/${r.id}`} className="text-brand underline underline-offset-4">
                      {r.number}
                    </Link>
                  </td>
                  <td>{r.supplier.name}</td>
                  <td>{r.purchaseOrder?.number ?? t('purchasing.receipts.free')}</td>
                  <td>{formatDateTime(r.receivedAt)}</td>
                  <td>{t(`purchasing.receipts.statuses.${r.status}`)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={receipts.data.page} onPage={(next) => update({ page: String(next) })} />
        </>
      )}
    </section>
  )
}
