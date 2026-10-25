import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { hasPermission, useCurrentUser } from '../auth'
import { CUSTOMER_TYPES, useCustomers } from './customerApi'

/**
 * `/customers` (frontend.md §4.6): search by name, phone (any format, ADR-110) or tax id, type and state filters
 * (active by default), link to each customer.
 */
export function CustomersPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const type = params.get('type') ?? ''
  const active = params.get('active') ?? 'true'
  const page = Number(params.get('page') ?? '0')
  const customers = useCustomers({ q, type, active: active === 'all' ? '' : active, page })
  const update = (next: Record<string, string>) =>
    setParams(Object.fromEntries(Object.entries({ q, type, active, ...next }).filter(([k, v]) => v !== '' && !(k === 'active' && v === 'true'))), { replace: true })

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{t('customers.title')}</h1>
        {hasPermission(current, 'customer.manage') && (
          <Link to="/customers/new" className="ms-auto h-10 px-4 inline-flex items-center rounded-ctl bg-brand text-brand-ink text-sm font-medium">
            {t('customers.new')}
          </Link>
        )}
      </div>
      <div className="flex flex-wrap gap-4 items-end">
        <div className="w-72">
          <TextField label={t('customers.search')} type="search" value={q} onChange={(e) => update({ q: e.target.value, page: '' })} />
        </div>
        <div className="w-48">
          <SelectField
            label={t('customers.type')}
            value={type}
            onChange={(e) => update({ type: e.target.value, page: '' })}
            options={[{ value: '', label: t('admin.all') }, ...CUSTOMER_TYPES.map((k) => ({ value: k, label: t(`customers.types.${k}`) }))]}
          />
        </div>
        <div className="w-48">
          <SelectField
            label={t('customers.activeFilter')}
            value={active}
            onChange={(e) => update({ active: e.target.value, page: '' })}
            options={[
              { value: 'true', label: t('customers.activeOnly') },
              { value: 'false', label: t('customers.inactiveOnly') },
              { value: 'all', label: t('admin.all') },
            ]}
          />
        </div>
      </div>
      {customers.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : customers.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(customers.error.problem, t)}</Alert>
      ) : !customers.data || customers.data.content.length === 0 ? (
        <p className="text-mute">{t('customers.empty')}</p>
      ) : (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('customers.name')}</th>
                <th className="text-start">{t('customers.type')}</th>
                <th className="text-start">{t('customers.category')}</th>
                <th className="text-start">{t('customers.place')}</th>
                <th className="text-start">{t('customers.phone')}</th>
                <th className="text-start ps-4">{t('customers.state')}</th>
              </tr>
            </thead>
            <tbody>
              {customers.data.content.map((c) => (
                <tr key={c.id} className="border-b border-line">
                  <td className="py-2">
                    <Link to={`/customers/${c.id}`} className="text-brand underline underline-offset-4">
                      {c.name}
                    </Link>
                  </td>
                  <td>{t(`customers.types.${c.customerType}`)}</td>
                  <td>{c.category?.name ?? '—'}</td>
                  <td>{[c.commune, c.wilaya].filter(Boolean).join(', ') || '—'}</td>
                  <td dir="ltr" className="text-start">
                    {c.phone ?? '—'}
                  </td>
                  <td className="ps-4">{t(c.active ? 'customers.active' : 'customers.inactive')}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={customers.data.page} onPage={(next) => update({ page: String(next) })} />
        </>
      )}
    </section>
  )
}
