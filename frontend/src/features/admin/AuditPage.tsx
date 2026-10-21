import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '../../components/Button'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { formatDateTime } from '../../lib/format'
import { useAuditLog, type AuditEntry, type AuditFilter } from './api'

/** Action codes of security.md §7, for the filter only; the server validates the value. */
const ACTIONS = [
  'PRICE_CHANGE', 'INVOICE_CANCEL', 'STOCK_CORRECTION', 'LOT_BLOCK', 'LOT_UNBLOCK', 'LOT_RECALL', 'RETURN_CREATE',
  'PAYMENT_REVERSE', 'CREDIT_LIMIT_CHANGE', 'USER_CREATE', 'USER_UPDATE', 'USER_DEACTIVATE', 'ROLE_PERMISSION_CHANGE',
  'PASSWORD_RESET', 'SETTING_CHANGE', 'BACKUP_RUN', 'BACKUP_DOWNLOAD', 'FEFO_OVERRIDE', 'DISCOUNT_OVERRIDE',
]

/** `/admin/audit` (FR-AUD-001, frontend.md §4): read-only, newest first, details as JSON. */
export function AuditPage() {
  const { t } = useTranslation()
  const [filter, setFilter] = useState<AuditFilter>({ entity: '', action: '', from: '', to: '', page: 0 })
  const [selected, setSelected] = useState<AuditEntry>()
  const audit = useAuditLog(filter)
  const update = (changes: Partial<AuditFilter>) => setFilter((f) => ({ ...f, page: 0, ...changes }))

  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{t('admin.audit.title')}</h1>
      <div className="grid grid-cols-4 gap-4 max-w-4xl">
        <SelectField
          label={t('admin.audit.action')}
          value={filter.action}
          onChange={(e) => update({ action: e.target.value })}
          options={[{ value: '', label: t('admin.all') }, ...ACTIONS.map((a) => ({ value: a, label: a }))]}
        />
        <TextField label={t('admin.audit.entity')} value={filter.entity} onChange={(e) => update({ entity: e.target.value })} />
        <TextField label={t('admin.audit.from')} type="date" value={filter.from} onChange={(e) => update({ from: e.target.value })} />
        <TextField label={t('admin.audit.to')} type="date" value={filter.to} onChange={(e) => update({ to: e.target.value })} />
      </div>
      {audit.data && audit.data.content.length === 0 && <p className="text-mute">{t('admin.audit.empty')}</p>}
      {audit.data && audit.data.content.length > 0 && (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('admin.audit.date')}</th>
                <th className="text-start">{t('admin.audit.user')}</th>
                <th className="text-start">{t('admin.audit.action')}</th>
                <th className="text-start">{t('admin.audit.entity')}</th>
                <th className="text-start">{t('admin.audit.ip')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {audit.data.content.map((e) => (
                <tr key={e.id} className="border-b border-line">
                  <td className="py-2">{formatDateTime(e.createdAt)}</td>
                  <td>{e.username ? `${e.username} (${e.userFullName})` : t('admin.audit.system')}</td>
                  <td>
                    <code>{e.action}</code>
                  </td>
                  <td>
                    {e.entity}
                    {e.entityId !== null && ` #${e.entityId}`}
                  </td>
                  <td>{e.ipAddress ?? '—'}</td>
                  <td>
                    <Button variant="secondary" onClick={() => setSelected(e)}>
                      {t('admin.audit.details')}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={audit.data.page} onPage={(page) => setFilter((f) => ({ ...f, page }))} />
        </>
      )}
      {selected && (
        <aside aria-label={t('admin.audit.details')} className="flex flex-col gap-3 p-4 border border-line rounded-ctl bg-surface">
          <div className="flex items-center gap-3">
            <h2 className="text-lg font-semibold">
              {selected.action} — {formatDateTime(selected.createdAt)}
            </h2>
            <Button variant="secondary" className="ms-auto" onClick={() => setSelected(undefined)}>
              {t('admin.close')}
            </Button>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <h3 className="text-sm font-medium mb-1">{t('admin.audit.oldValues')}</h3>
              <pre dir="ltr" className="text-xs bg-bg p-2 rounded-ctl overflow-auto">
                {JSON.stringify(selected.oldValues, null, 2)}
              </pre>
            </div>
            <div>
              <h3 className="text-sm font-medium mb-1">{t('admin.audit.newValues')}</h3>
              <pre dir="ltr" className="text-xs bg-bg p-2 rounded-ctl overflow-auto">
                {JSON.stringify(selected.newValues, null, 2)}
              </pre>
            </div>
          </div>
        </aside>
      )}
    </section>
  )
}
