import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { formatDateTime, formatMoney } from '../../lib/format'
import type { SaleSummary } from './saleApi'

/** A draft has no number yet (ADR-122), so its link says so. */
export function SalesTable({ sales }: { sales: SaleSummary[] }) {
  const { t } = useTranslation()
  return (
    <table className="w-full text-sm border-collapse">
      <thead>
        <tr className="border-b border-line">
          <th className="text-start py-2">{t('sales.number')}</th>
          <th className="text-start">{t('sales.type')}</th>
          <th className="text-start">{t('sales.customer')}</th>
          <th className="text-start">{t('sales.date')}</th>
          <th className="text-start">{t('sales.status')}</th>
          <th className="text-end">{t('sales.total')}</th>
        </tr>
      </thead>
      <tbody>
        {sales.map((s) => (
          <tr key={s.id} className="border-b border-line">
            <td className="py-2">
              <Link to={`/sales/${s.id}`} className="text-brand underline underline-offset-4">
                {s.number ?? t('sales.draftOf', { id: s.id })}
              </Link>
            </td>
            <td>
              {t(`pos.types.${s.docType}`, { defaultValue: s.docType })}
              {s.creditSale && ` · ${t('sales.onCredit')}`}
            </td>
            <td>{s.customer?.name ?? t('sales.anonymous')}</td>
            <td>{formatDateTime(s.issuedAt ?? s.createdAt)}</td>
            <td>{t(`sales.statuses.${s.status}`)}</td>
            <td className="text-end">{formatMoney(s.totalTtc)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
