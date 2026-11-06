import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { ColumnChart } from '../../components/ColumnChart'
import { PdfLink } from '../../components/PdfLink'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { hasPermission, useCurrentUser } from '../auth/api'
import { reportQuery, useReport, type ReportParams } from './api'
import { REPORTS, reportByKey, type ReportDefinition, type Section } from './definitions'

/** `/reports` (frontend.md §4.10): the reports the user may open, by their permission (BR-RPT-003). */
export function ReportsPage() {
  const { t } = useTranslation()
  const { data: user } = useCurrentUser()
  const visible = REPORTS.filter((r) => hasPermission(user, r.permission))
  return (
    <section className="flex flex-col gap-4 max-w-3xl">
      <h1 className="text-2xl font-semibold">{t('reports.title')}</h1>
      {visible.length === 0 && <p className="text-mute">{t('reports.none')}</p>}
      <ul className="grid grid-cols-2 gap-3">
        {visible.map((r) => (
          <li key={r.key}>
            <Link to={`/reports/${r.key}`} className="block p-4 rounded-ctl border border-line bg-surface hover:bg-bg">
              <span className="font-medium">{t(`reports.${r.key}.title`)}</span>
              <span className="block text-sm text-mute">{t(`reports.${r.key}.description`)}</span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** `/reports/:key`: filters, the table and its totals, CSV and PDF of the same figures (ADR-149). */
export function ReportPage() {
  const { t } = useTranslation()
  const definition = reportByKey(useParams().key)
  const { data: user } = useCurrentUser()
  if (!definition) {
    return <Alert tone="danger">{t('reports.unknown')}</Alert>
  }
  if (!hasPermission(user, definition.permission)) {
    return <Alert tone="danger">{t('errors.ACCESS_DENIED')}</Alert>
  }
  return <ReportView key={definition.key} definition={definition} canSeeCost={hasPermission(user, 'report.margin')} />
}

function ReportView({ definition, canSeeCost }: { definition: ReportDefinition; canSeeCost: boolean }) {
  const { t } = useTranslation()
  const [search] = useSearchParams()
  const [params, setParams] = useState<ReportParams>(() => {
    const initial: ReportParams = {}
    for (const name of ['from', 'to', 'groupBy', 'customerType', 'basis', 'supplierId', 'cashierId', 'lotId']) {
      initial[name] = search.get(name) ?? ''
    }
    return initial
  })
  const ready = (definition.required ?? []).every((name) => params[name] !== '')
  const report = useReport<unknown>(definition.path, params, ready)
  const set = (name: string, value: string) => setParams((p) => ({ ...p, [name]: value }))
  const has = (filter: string) => definition.filters.includes(filter as never)
  const exportUrl = (format: 'csv' | 'pdf') => `/api/reports/${definition.path}?${reportQuery(params, format)}`

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-3 flex-wrap">
        <h1 className="text-2xl font-semibold">{t(`reports.${definition.key}.title`)}</h1>
        <Link to="/reports" className="text-sm text-brand underline ms-auto">
          {t('reports.back')}
        </Link>
      </div>
      <p className="text-sm text-mute max-w-3xl">{t(`reports.${definition.key}.description`)}</p>
      <div className="grid grid-cols-4 gap-4 max-w-4xl" role="group" aria-label={t('reports.filters')}>
        {has('period') && (
          <>
            <TextField label={t('reports.from')} type="date" value={params.from} onChange={(e) => set('from', e.target.value)} />
            <TextField label={t('reports.to')} type="date" value={params.to} onChange={(e) => set('to', e.target.value)} />
          </>
        )}
        {definition.groups && (
          <SelectField
            label={t('reports.groupBy')}
            value={params.groupBy}
            onChange={(e) => set('groupBy', e.target.value)}
            options={definition.groups.map((g, i) => ({ value: i === 0 ? '' : g, label: t(`reports.group.${g}`) }))}
          />
        )}
        {has('customerType') && (
          <SelectField
            label={t('reports.customerType')}
            value={params.customerType}
            onChange={(e) => set('customerType', e.target.value)}
            options={['', 'breeder', 'veterinarian', 'other'].map((v) => ({ value: v, label: t(`reports.customerTypes.${v || 'all'}`) }))}
          />
        )}
        {has('basis') && (
          <SelectField
            label={t('reports.basis')}
            value={params.basis}
            onChange={(e) => set('basis', e.target.value)}
            options={[
              { value: '', label: t('reports.bases.sale_price') },
              ...(canSeeCost ? [{ value: 'cost', label: t('reports.bases.cost') }] : []),
            ]}
          />
        )}
        {has('supplierId') && (
          <TextField label={t('reports.supplierId')} inputMode="numeric" value={params.supplierId} onChange={(e) => set('supplierId', e.target.value.replace(/\D/g, ''))} />
        )}
        {has('cashierId') && (
          <TextField label={t('reports.cashierId')} inputMode="numeric" value={params.cashierId} onChange={(e) => set('cashierId', e.target.value.replace(/\D/g, ''))} />
        )}
        {has('lotId') && (
          <TextField label={t('reports.lotId')} inputMode="numeric" value={params.lotId} onChange={(e) => set('lotId', e.target.value.replace(/\D/g, ''))} />
        )}
      </div>
      {ready && (
        <div className="flex gap-3">
          <a href={exportUrl('csv')} download className="h-10 px-4 inline-flex items-center rounded-ctl border border-control text-sm font-medium">
            {t('reports.exportCsv')}
          </a>
          <PdfLink href={exportUrl('pdf')}>{t('reports.exportPdf')}</PdfLink>
        </div>
      )}
      {!ready && <p className="text-mute">{t('reports.needLot')}</p>}
      {report.error instanceof ApiError && <Alert tone="danger">{problemMessage(report.error.problem, t)}</Alert>}
      {report.isPending && ready && (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      )}
      {report.data !== undefined && !report.error && <ReportChart definition={definition} data={report.data} />}
      {report.data !== undefined && !report.error && definition.sections(report.data, t).map((s, i) => <SectionTable key={i} section={s} />)}
    </section>
  )
}

function ReportChart({ definition, data }: { definition: ReportDefinition; data: unknown }) {
  const { t } = useTranslation()
  const chart = definition.chart?.(data, t) ?? null
  return chart === null ? null : <ColumnChart title={chart.title} points={chart.points} />
}

function SectionTable({ section }: { section: Section }) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col gap-2 overflow-x-auto">
      {section.heading && <h2 className="text-lg font-semibold">{section.heading}</h2>}
      <table className="w-full text-sm border-collapse">
        <thead>
          <tr className="border-b border-line">
            {section.columns.map((c, i) => (
              <th key={i} className={`py-2 px-1 ${c.num ? 'text-end' : 'text-start'}`}>
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {section.rows.length === 0 && (
            <tr>
              <td colSpan={section.columns.length} className="py-2 text-mute">
                {t('reports.empty')}
              </td>
            </tr>
          )}
          {section.rows.map((row, r) => (
            <tr key={r} className="border-b border-line">
              {row.map((cell, i) => (
                <td key={i} className={`py-2 px-1 ${cell.num ? 'text-end tabular-nums' : ''}`}>
                  {cell.text}
                </td>
              ))}
            </tr>
          ))}
          {section.totals.map((row, r) => (
            <tr key={`t${r}`} className="font-semibold border-t-2 border-line">
              {row.map((cell, i) => (
                <td key={i} className={`py-2 px-1 ${cell.num ? 'text-end tabular-nums' : ''}`}>
                  {cell.text}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
