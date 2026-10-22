import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatQuantity } from '../../lib/format'
import { AlertBadge, LotStatusBadge } from './LotBadges'
import { ALERT_LEVELS, LOT_STATUSES, useLots, type LotSearch, type LotSummary } from './lotApi'

const FILTERS = ['productId', 'status', 'alertLevel', 'expiringWithinDays', 'q'] as const

/**
 * `/lots` (frontend.md §4.2): filters by product, status, alert level, expiry window and stock, kept in the URL; badges show
 * the status and level returned by the API. Lots appear with opening stock and receipts (ADR-064).
 */
export function LotsPage() {
  const { t } = useTranslation()
  const [params, setParams] = useSearchParams()
  const search: LotSearch = {
    productId: params.get('productId') ?? '',
    status: params.get('status') ?? '',
    alertLevel: params.get('alertLevel') ?? '',
    expiringWithinDays: params.get('expiringWithinDays') ?? '',
    hasStock: params.get('hasStock') === 'true',
    q: params.get('q') ?? '',
    page: Number(params.get('page') ?? '0'),
  }
  const lots = useLots(search)
  const filtered = search.hasStock || FILTERS.some((f) => search[f] !== '')

  const update = (key: string, value: string) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (value === '' || value === 'false') {
          next.delete(key)
        } else {
          next.set(key, value)
        }
        if (key !== 'page') {
          next.delete('page')
        }
        return next
      },
      { replace: true },
    )

  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{t('lots.title')}</h1>
      <div className="grid grid-cols-5 gap-3 items-end">
        <TextField label={t('lots.lotNumber')} type="search" value={search.q} onChange={(e) => update('q', e.target.value)} />
        <SelectField
          label={t('lots.statusFilter')}
          value={search.status}
          onChange={(e) => update('status', e.target.value)}
          options={[{ value: '', label: t('admin.all') }, ...LOT_STATUSES.map((s) => ({ value: s, label: t(`lots.status.${s}`) }))]}
        />
        <SelectField
          label={t('lots.levelFilter')}
          value={search.alertLevel}
          onChange={(e) => update('alertLevel', e.target.value)}
          options={[{ value: '', label: t('admin.all') }, ...ALERT_LEVELS.map((l) => ({ value: l, label: t(`lots.level.${l}`) }))]}
        />
        <TextField
          label={t('lots.expiringWithin')}
          type="number"
          min={0}
          value={search.expiringWithinDays}
          onChange={(e) => update('expiringWithinDays', e.target.value)}
        />
        <label className="flex items-center gap-2 text-sm pb-2">
          <input type="checkbox" checked={search.hasStock} onChange={(e) => update('hasStock', String(e.target.checked))} />
          {t('lots.hasStock')}
        </label>
      </div>
      {search.productId !== '' && (
        <p className="text-sm">
          {t('lots.productFilter')}{' '}
          <button type="button" className="underline text-brand" onClick={() => update('productId', '')}>
            {t('lots.clearProduct')}
          </button>
        </p>
      )}
      {lots.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : lots.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(lots.error.problem, t)}</Alert>
      ) : !lots.data || lots.data.content.length === 0 ? (
        <p className="text-mute">{filtered ? t('lots.noMatch') : t('lots.empty')}</p>
      ) : (
        <>
          <LotTable lots={lots.data.content} showProduct />
          <Pager page={lots.data.page} onPage={(page) => update('page', String(page))} />
        </>
      )}
    </section>
  )
}

/** Shared by the list and the product's Lots tab. */
export function LotTable({ lots, showProduct }: { lots: LotSummary[]; showProduct: boolean }) {
  const { t } = useTranslation()
  return (
    <table className="w-full text-sm border-collapse">
      <thead>
        <tr className="border-b border-line">
          <th className="text-start py-2">{t('lots.lotNumber')}</th>
          {showProduct && <th className="text-start">{t('lots.product')}</th>}
          <th className="text-start">{t('lots.expiry')}</th>
          <th className="text-end">{t('lots.daysLeft')}</th>
          <th className="text-end">{t('lots.quantity')}</th>
          <th className="text-start ps-4">{t('lots.statusFilter')}</th>
        </tr>
      </thead>
      <tbody>
        {lots.map((lot) => (
          <tr key={lot.id} className="border-b border-line">
            <td className="py-2">
              <Link to={`/lots/${lot.id}`} className="text-brand underline underline-offset-4">
                {lot.lotNumber}
              </Link>
            </td>
            {showProduct && <td>{lot.product.commercialName}</td>}
            <td>
              <span className="me-2">{formatDate(lot.expiryDate)}</span>
              <AlertBadge level={lot.alertLevel} />
            </td>
            <td className="text-end">{lot.daysLeft}</td>
            <td className="text-end">{`${formatQuantity(lot.quantityOnHand)} ${lot.product.baseUnit}`}</td>
            <td className="ps-4">
              <LotStatusBadge status={lot.status} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
