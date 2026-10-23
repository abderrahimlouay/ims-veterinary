import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { formatDate, formatDateTime, formatQuantity } from '../../lib/format'
import { isNegativeDecimal } from '../../lib/decimal'
import type { MovementEntry } from './stockApi'

/**
 * The ledger (frontend.md §4.3): read-only; a signed quantity shows its direction by a +/− sign **and** a colour,
 * never by colour alone. Shared by the ledger view and the lot page.
 */
export function MovementsTable({ movements, showLot }: { movements: MovementEntry[]; showLot: boolean }) {
  const { t } = useTranslation()
  return (
    <table className="w-full text-sm border-collapse">
      <thead>
        <tr className="border-b border-line">
          <th className="text-start py-2">{t('stock.movements.date')}</th>
          {showLot && <th className="text-start">{t('stock.movements.lot')}</th>}
          <th className="text-start">{t('stock.movements.type')}</th>
          <th className="text-end">{t('stock.movements.quantity')}</th>
          <th className="text-start ps-4">{t('stock.movements.reason')}</th>
          <th className="text-start">{t('stock.movements.user')}</th>
        </tr>
      </thead>
      <tbody>
        {movements.map((m) => {
          const negative = isNegativeDecimal(m.quantity)
          return (
            <tr key={m.id} className="border-b border-line">
              <td className="py-2">{formatDateTime(m.createdAt)}</td>
              {showLot && (
                <td>
                  <Link to={`/lots/${m.lot.id}`} className="text-brand underline underline-offset-4">
                    {m.lot.lotNumber}
                  </Link>{' '}
                  <span className="text-mute">
                    {m.product.commercialName} · {formatDate(m.lot.expiryDate)}
                  </span>
                </td>
              )}
              <td>{t(`stock.movementType.${m.type}`)}</td>
              <td className={`text-end font-medium ${negative ? 'text-danger' : 'text-ok'}`}>
                {`${negative ? '−' : '+'}${formatQuantity(m.quantity.replace('-', ''))} ${m.product.baseUnit}`}
              </td>
              <td className="ps-4">{m.reason ?? '—'}</td>
              <td>{m.user.fullName}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
