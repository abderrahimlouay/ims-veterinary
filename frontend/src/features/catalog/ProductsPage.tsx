import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatUnitPrice } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { findByBarcode, useProducts, type ProductSearch } from './productApi'
import { referenceLabel, useReferenceList, type ReferenceListPath } from './referenceApi'

/** frontend.md §15: the query follows the typing after a short pause. */
const DEBOUNCE_MS = 250
const FILTERS = ['categoryId', 'manufacturerId', 'ingredientId', 'speciesId'] as const

function FilterSelect({ path, i18nKey, label, value, onChange }: {
  path: ReferenceListPath
  i18nKey: string
  label: string
  value: string
  onChange: (value: string) => void
}) {
  const { t } = useTranslation()
  const items = useReferenceList(path)
  return (
    <SelectField
      label={label}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      options={[{ value: '', label: t('admin.all') }, ...(items.data ?? []).map((r) => ({ value: String(r.id), label: referenceLabel(r, i18nKey, t) }))]}
    />
  )
}

/**
 * `/products` (frontend.md §4.1): search anywhere in name, SKU or active ingredient (ADR-048); a scanner's
 * Enter-terminated barcode opens its product (FR-PROD-003); filters by
 * category, manufacturer, ingredient and species (ADR-047), archived on request. Criteria live in the URL.
 */
export function ProductsPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const search: ProductSearch = {
    q: params.get('q') ?? '',
    categoryId: params.get('categoryId') ?? '',
    manufacturerId: params.get('manufacturerId') ?? '',
    ingredientId: params.get('ingredientId') ?? '',
    speciesId: params.get('speciesId') ?? '',
    includeArchived: params.get('includeArchived') === 'true',
    page: Number(params.get('page') ?? '0'),
  }
  const [typed, setTyped] = useState(search.q)
  /**
   * The controls show this local copy: a URL change lands after React re-renders, so a control bound to the
   * URL alone flickers back for a moment (e2e J2 caught it on the archived checkbox). Re-synced from the URL.
   */
  const [controls, setControls] = useState(search)
  const urlKey = params.toString()
  const [syncedKey, setSyncedKey] = useState(urlKey)
  if (syncedKey !== urlKey) {
    setSyncedKey(urlKey)
    setControls(search)
  }
  const products = useProducts(search)
  const filtered = search.q !== '' || FILTERS.some((f) => search[f] !== '')

  const update = (changes: Record<string, string>) => {
    setControls((c) => ({
      ...c,
      ...Object.fromEntries(Object.entries(changes).map(([k, v]) => [k, k === 'includeArchived' ? v === 'true' : v])),
    }))
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
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
  }

  useEffect(() => {
    const timer = setTimeout(
      () =>
        setParams(
          (current) => {
            if ((current.get('q') ?? '') === typed) {
              return current
            }
            const next = new URLSearchParams(current)
            if (typed === '') {
              next.delete('q')
            } else {
              next.set('q', typed)
            }
            next.delete('page')
            return next
          },
          { replace: true },
        ),
      DEBOUNCE_MS,
    )
    return () => clearTimeout(timer)
  }, [typed, setParams])

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{t('catalog.products.title')}</h1>
        {hasPermission(current, 'product.create') && (
          <Link
            to="/products/new"
            className="ms-auto inline-flex items-center h-10 px-4 rounded-ctl bg-brand text-brand-ink text-sm font-medium hover:bg-brand/90 focus:outline-2 focus:outline-offset-2 focus:outline-brand"
          >
            {t('catalog.products.new')}
          </Link>
        )}
      </div>
      <div className="grid grid-cols-5 gap-3">
        <div className="col-span-5 max-w-xl">
          <TextField
            label={t('catalog.products.search')}
            type="search"
            value={typed}
            placeholder={t('catalog.products.searchHint')}
            onChange={(e) => setTyped(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && typed !== '') {
                void findByBarcode(typed).then((found) => found && navigate(`/products/${found.product.id}`), () => undefined)
              }
            }}
          />
        </div>
        <FilterSelect path="categories" i18nKey="categories" label={t('catalog.products.category')} value={controls.categoryId} onChange={(v) => update({ categoryId: v })} />
        <FilterSelect path="manufacturers" i18nKey="manufacturers" label={t('catalog.products.manufacturer')} value={controls.manufacturerId} onChange={(v) => update({ manufacturerId: v })} />
        <FilterSelect path="active-ingredients" i18nKey="ingredients" label={t('catalog.products.ingredient')} value={controls.ingredientId} onChange={(v) => update({ ingredientId: v })} />
        <FilterSelect path="animal-species" i18nKey="species" label={t('catalog.products.species')} value={controls.speciesId} onChange={(v) => update({ speciesId: v })} />
        <label className="flex items-end gap-2 text-sm pb-2">
          <input
            type="checkbox"
            checked={controls.includeArchived}
            onChange={(e) => update({ includeArchived: String(e.target.checked) })}
          />
          {t('catalog.products.showArchived')}
        </label>
      </div>
      {products.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : products.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(products.error.problem, t)}</Alert>
      ) : products.data && products.data.content.length === 0 ? (
        <p className="text-mute">{filtered ? t('catalog.products.noMatch') : t('catalog.products.empty')}</p>
      ) : (
        products.data && (
          <>
            <table className="w-full text-sm border-collapse">
              <thead>
                <tr className="border-b border-line">
                  <th className="text-start py-2">{t('catalog.products.sku')}</th>
                  <th className="text-start">{t('catalog.products.name')}</th>
                  <th className="text-start">{t('catalog.products.category')}</th>
                  <th className="text-start">{t('catalog.products.baseUnit')}</th>
                  <th className="text-end">{t('catalog.products.salePrice')}</th>
                </tr>
              </thead>
              <tbody>
                {products.data.content.map((p) => (
                  <tr key={p.id} className="border-b border-line">
                    <td className="py-2">
                      <code>{p.sku}</code>
                    </td>
                    <td>
                      <Link to={`/products/${p.id}`} className="text-brand underline underline-offset-4">
                        {p.commercialName}
                      </Link>
                      {p.archived && <span className="ms-2 text-xs text-mute">{t('catalog.products.archived')}</span>}
                    </td>
                    <td>{p.category ? referenceLabel(p.category, 'categories', t) : '—'}</td>
                    <td>{p.baseUnit}</td>
                    <td className="text-end">{formatUnitPrice(p.salePrice)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager page={products.data.page} onPage={(page) => update({ page: String(page) })} />
          </>
        )
      )}
    </section>
  )
}
