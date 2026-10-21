import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Pager } from '../../components/Pager'
import { problemMessage } from '../../lib/errors'
import { formatUnitPrice } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { useProducts } from './productApi'
import { referenceLabel } from './referenceApi'

/** `/products` (frontend.md §4.1); search and filters arrive with the search slice (ADR-048). */
export function ProductsPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const [filter, setFilter] = useState({ includeArchived: false, page: 0 })
  const products = useProducts(filter)

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
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={filter.includeArchived}
          onChange={(e) => setFilter({ includeArchived: e.target.checked, page: 0 })}
        />
        {t('catalog.products.showArchived')}
      </label>
      {products.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : products.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(products.error.problem, t)}</Alert>
      ) : products.data && products.data.content.length === 0 ? (
        <p className="text-mute">{t('catalog.products.empty')}</p>
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
            <Pager page={products.data.page} onPage={(page) => setFilter((f) => ({ ...f, page }))} />
          </>
        )
      )}
    </section>
  )
}
