import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatMoney } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { ORDER_STATUSES, useOrders } from './orderApi'
import { useSuppliers } from './supplierApi'

/** `/purchase-orders` (frontend.md §4.5): orders newest first, by status and supplier. */
export function PurchaseOrdersPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const supplierId = params.get('supplierId') ?? ''
  const page = Number(params.get('page') ?? '0')
  const orders = useOrders(status, supplierId, page)
  const suppliers = useSuppliers('', '', 0, 100)
  const update = (next: Record<string, string>) =>
    setParams(Object.fromEntries(Object.entries({ status, supplierId, ...next }).filter(([, v]) => v !== '')), { replace: true })

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{t('purchasing.orders.title')}</h1>
        {hasPermission(current, 'purchase.create') && (
          <Link to="/purchase-orders/new" className="ms-auto h-10 px-4 inline-flex items-center rounded-ctl bg-brand text-brand-ink text-sm font-medium">
            {t('purchasing.orders.new')}
          </Link>
        )}
      </div>
      <div className="flex flex-wrap gap-4">
        <div className="w-56">
          <SelectField
            label={t('purchasing.orders.status')}
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}
            options={[{ value: '', label: t('admin.all') }, ...ORDER_STATUSES.map((s) => ({ value: s, label: t(`purchasing.orders.statuses.${s}`) }))]}
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
      {orders.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : orders.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(orders.error.problem, t)}</Alert>
      ) : !orders.data || orders.data.content.length === 0 ? (
        <p className="text-mute">{t('purchasing.orders.empty')}</p>
      ) : (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('purchasing.orders.number')}</th>
                <th className="text-start">{t('purchasing.orders.supplier')}</th>
                <th className="text-start">{t('purchasing.orders.orderDate')}</th>
                <th className="text-start">{t('purchasing.orders.expectedDate')}</th>
                <th className="text-start">{t('purchasing.orders.status')}</th>
                <th className="text-end">{t('purchasing.orders.totalTtc')}</th>
              </tr>
            </thead>
            <tbody>
              {orders.data.content.map((o) => (
                <tr key={o.id} className="border-b border-line">
                  <td className="py-2">
                    <Link to={`/purchase-orders/${o.id}`} className="text-brand underline underline-offset-4">
                      {o.number}
                    </Link>
                  </td>
                  <td>{o.supplier.name}</td>
                  <td>{formatDate(o.orderDate)}</td>
                  <td>{o.expectedDate ? formatDate(o.expectedDate) : '—'}</td>
                  <td>{t(`purchasing.orders.statuses.${o.status}`)}</td>
                  <td className="text-end">{formatMoney(o.totalTtc)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={orders.data.page} onPage={(next) => update({ page: String(next) })} />
        </>
      )}
    </section>
  )
}
