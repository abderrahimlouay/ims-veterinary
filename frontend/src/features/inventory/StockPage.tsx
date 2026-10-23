import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatQuantity } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { MovementsTable } from './MovementsTable'
import { BelowReorderBadge, StockStatusBadge } from './StockBadges'
import { MOVEMENT_TYPES, STOCK_STATUSES, useMovements, useStockLevels, type MovementSearch, type StockSearch } from './stockApi'

const ACTION_LINK =
  'inline-flex items-center h-10 px-4 rounded-ctl text-sm font-medium focus:outline-2 focus:outline-offset-2 focus:outline-brand'

/**
 * `/stock` (frontend.md §4.3): stock levels (sellable vs total, thresholds, status, below reorder; ADR-080) and the
 * movement ledger (`?view=movements`), criteria kept in the URL. Links to a manual adjustment and to opening stock
 * for `stock.adjust` holders.
 */
export function StockPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const [params, setParams] = useSearchParams()
  const view = params.get('view') === 'movements' ? 'movements' : 'levels'

  const update = (changes: Record<string, string>) =>
    setParams(
      (now) => {
        const next = new URLSearchParams(now)
        for (const [key, value] of Object.entries(changes)) {
          if (value === '' || value === 'false') {
            next.delete(key)
          } else {
            next.set(key, value)
          }
        }
        if (!('page' in changes)) {
          next.delete('page')
        }
        return next
      },
      { replace: true },
    )

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-3 flex-wrap">
        <h1 className="text-2xl font-semibold">{t('stock.title')}</h1>
        {hasPermission(current, 'stock.adjust') && (
          <div className="ms-auto flex gap-3">
            <Link to="/stock/adjustments/new" className={`${ACTION_LINK} bg-brand text-brand-ink hover:bg-brand/90`}>
              {t('stock.adjustment.new')}
            </Link>
            <Link to="/stock/opening-stock/new" className={`${ACTION_LINK} bg-surface text-ink border border-control hover:bg-bg`}>
              {t('stock.opening.new')}
            </Link>
          </div>
        )}
      </div>
      <div role="tablist" aria-label={t('stock.views')} className="flex gap-2 border-b border-line">
        {(['levels', 'movements'] as const).map((v) => (
          <button
            key={v}
            type="button"
            role="tab"
            aria-selected={view === v}
            onClick={() => setParams(v === 'levels' ? {} : { view: v }, { replace: true })}
            className={`px-3 py-2 text-sm font-medium border-b-2 focus:outline-2 focus:outline-brand ${view === v ? 'border-brand text-brand' : 'border-transparent text-ink'}`}
          >
            {t(`stock.view.${v}`)}
          </button>
        ))}
      </div>
      {view === 'levels' ? <Levels params={params} update={update} /> : <Ledger params={params} update={update} />}
    </section>
  )
}

type ViewProps = { params: URLSearchParams; update: (changes: Record<string, string>) => void }

function Levels({ params, update }: ViewProps) {
  const { t } = useTranslation()
  const search: StockSearch = {
    q: params.get('q') ?? '',
    status: params.get('status') ?? '',
    belowReorder: params.get('belowReorder') === 'true',
    page: Number(params.get('page') ?? '0'),
  }
  const levels = useStockLevels(search)
  const filtered = search.q !== '' || search.status !== '' || search.belowReorder
  return (
    <div role="tabpanel" className="flex flex-col gap-4">
      <div className="grid grid-cols-4 gap-3 items-end">
        <TextField label={t('stock.search')} type="search" value={search.q} onChange={(e) => update({ q: e.target.value })} />
        <SelectField
          label={t('stock.statusFilter')}
          value={search.status}
          onChange={(e) => update({ status: e.target.value })}
          options={[{ value: '', label: t('admin.all') }, ...STOCK_STATUSES.map((s) => ({ value: s, label: t(`stock.status.${s}`) }))]}
        />
        <label className="flex items-center gap-2 text-sm pb-2">
          <input
            type="checkbox"
            checked={search.belowReorder}
            onChange={(e) => update({ belowReorder: String(e.target.checked) })}
          />
          {t('stock.belowReorderOnly')}
        </label>
      </div>
      {levels.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : levels.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(levels.error.problem, t)}</Alert>
      ) : !levels.data || levels.data.content.length === 0 ? (
        <p className="text-mute">{filtered ? t('stock.noMatch') : t('stock.empty')}</p>
      ) : (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('catalog.products.sku')}</th>
                <th className="text-start">{t('catalog.products.name')}</th>
                <th className="text-end">{t('stock.sellable')}</th>
                <th className="text-end">{t('stock.total')}</th>
                <th className="text-end">{t('catalog.products.minStock')}</th>
                <th className="text-end">{t('catalog.products.reorderPoint')}</th>
                <th className="text-start ps-4">{t('stock.statusFilter')}</th>
                <th className="text-start">{t('stock.nearestExpiry')}</th>
              </tr>
            </thead>
            <tbody>
              {levels.data.content.map((level) => (
                <tr key={level.product.id} className="border-b border-line">
                  <td className="py-2">
                    <code>{level.product.sku}</code>
                  </td>
                  <td>
                    <Link to={`/products/${level.product.id}`} className="text-brand underline underline-offset-4">
                      {level.product.commercialName}
                    </Link>
                  </td>
                  <td className="text-end">{`${formatQuantity(level.sellableQty)} ${level.product.baseUnit}`}</td>
                  <td className="text-end">{formatQuantity(level.totalQty)}</td>
                  <td className="text-end">{formatQuantity(level.minStock)}</td>
                  <td className="text-end">{formatQuantity(level.reorderPoint)}</td>
                  <td className="ps-4">
                    <span className="flex gap-2 flex-wrap">
                      <StockStatusBadge status={level.status} />
                      {level.belowReorder && <BelowReorderBadge />}
                    </span>
                  </td>
                  <td>{level.nearestSellableExpiry ? formatDate(level.nearestSellableExpiry) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={levels.data.page} onPage={(page) => update({ page: String(page) })} />
        </>
      )}
    </div>
  )
}

function Ledger({ params, update }: ViewProps) {
  const { t } = useTranslation()
  const search: MovementSearch = {
    productId: params.get('productId') ?? '',
    lotId: params.get('lotId') ?? '',
    type: params.get('type') ?? '',
    from: params.get('from') ?? '',
    to: params.get('to') ?? '',
    page: Number(params.get('page') ?? '0'),
  }
  const movements = useMovements(search)
  return (
    <div role="tabpanel" className="flex flex-col gap-4">
      <div className="grid grid-cols-4 gap-3 items-end">
        <SelectField
          label={t('stock.movements.type')}
          value={search.type}
          onChange={(e) => update({ type: e.target.value })}
          options={[{ value: '', label: t('admin.all') }, ...MOVEMENT_TYPES.map((type) => ({ value: type, label: t(`stock.movementType.${type}`) }))]}
        />
        <TextField label={t('stock.movements.from')} type="date" value={search.from} onChange={(e) => update({ from: e.target.value })} />
        <TextField label={t('stock.movements.to')} type="date" value={search.to} onChange={(e) => update({ to: e.target.value })} />
      </div>
      {(search.productId !== '' || search.lotId !== '') && (
        <p className="text-sm">
          {t('stock.movements.limited')}{' '}
          <button type="button" className="underline text-brand" onClick={() => update({ productId: '', lotId: '' })}>
            {t('stock.movements.showAll')}
          </button>
        </p>
      )}
      {movements.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : movements.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(movements.error.problem, t)}</Alert>
      ) : !movements.data || movements.data.content.length === 0 ? (
        <p className="text-mute">{t('stock.movements.empty')}</p>
      ) : (
        <>
          <MovementsTable movements={movements.data.content} showLot />
          <Pager page={movements.data.page} onPage={(page) => update({ page: String(page) })} />
        </>
      )}
    </div>
  )
}
