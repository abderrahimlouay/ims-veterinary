import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { hasPermission, useCurrentUser } from '../auth'
import { useSuppliers } from './supplierApi'

/** `/suppliers` (frontend.md §4.5): search by name, active filter (active by default), link to each supplier. */
export function SuppliersPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const active = params.get('active') ?? 'true'
  const page = Number(params.get('page') ?? '0')
  const suppliers = useSuppliers(q, active === 'all' ? '' : active, page)
  const update = (next: Record<string, string>) =>
    setParams(Object.fromEntries(Object.entries({ q, active, ...next }).filter(([k, v]) => v !== '' && !(k === 'active' && v === 'true'))), { replace: true })

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{t('purchasing.suppliers.title')}</h1>
        {hasPermission(current, 'supplier.manage') && (
          <Link to="/suppliers/new" className="ms-auto h-10 px-4 inline-flex items-center rounded-ctl bg-brand text-brand-ink text-sm font-medium">
            {t('purchasing.suppliers.new')}
          </Link>
        )}
      </div>
      <div className="flex flex-wrap gap-4 items-end">
        <div className="w-72">
          <TextField label={t('purchasing.suppliers.search')} type="search" value={q} onChange={(e) => update({ q: e.target.value, page: '' })} />
        </div>
        <div className="w-48">
          <SelectField
            label={t('purchasing.suppliers.activeFilter')}
            value={active}
            onChange={(e) => update({ active: e.target.value, page: '' })}
            options={[
              { value: 'true', label: t('purchasing.suppliers.activeOnly') },
              { value: 'false', label: t('purchasing.suppliers.inactiveOnly') },
              { value: 'all', label: t('admin.all') },
            ]}
          />
        </div>
      </div>
      {suppliers.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : suppliers.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(suppliers.error.problem, t)}</Alert>
      ) : !suppliers.data || suppliers.data.content.length === 0 ? (
        <p className="text-mute">{t('purchasing.suppliers.empty')}</p>
      ) : (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('purchasing.suppliers.name')}</th>
                <th className="text-start">{t('purchasing.suppliers.kind')}</th>
                <th className="text-start">{t('purchasing.suppliers.phone')}</th>
                <th className="text-end">{t('purchasing.suppliers.paymentTermsDays')}</th>
                <th className="text-start ps-4">{t('purchasing.suppliers.state')}</th>
              </tr>
            </thead>
            <tbody>
              {suppliers.data.content.map((s) => (
                <tr key={s.id} className="border-b border-line">
                  <td className="py-2">
                    <Link to={`/suppliers/${s.id}`} className="text-brand underline underline-offset-4">
                      {s.name}
                    </Link>
                  </td>
                  <td>{t(`purchasing.suppliers.kinds.${s.kind}`)}</td>
                  <td>{s.phone ?? '—'}</td>
                  <td className="text-end">{s.paymentTermsDays}</td>
                  <td className="ps-4">{t(s.active ? 'purchasing.suppliers.active' : 'purchasing.suppliers.inactive')}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={suppliers.data.page} onPage={(next) => update({ page: String(next) })} />
        </>
      )}
    </section>
  )
}
