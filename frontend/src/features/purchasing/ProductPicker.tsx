import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { useProducts, type ProductSummary } from '../catalog'

/** Searches products not archived (they cannot be ordered, BR-PROD-004) and returns the one picked. */
export function ProductPicker({ onPick, label }: { onPick: (product: ProductSummary) => void; label?: string }) {
  const { t } = useTranslation()
  const [q, setQ] = useState('')
  const products = useProducts({ q, categoryId: '', manufacturerId: '', ingredientId: '', speciesId: '', includeArchived: false, inStockOnly: false, page: 0 }, 10)
  return (
    <div className="flex flex-col gap-2">
      <TextField label={label ?? t('purchasing.findProduct')} type="search" value={q} onChange={(e) => setQ(e.target.value)} />
      {q.trim() !== '' &&
        (products.isPending ? (
          <p role="status" className="text-mute">
            {t('app.loading')}
          </p>
        ) : products.error instanceof ApiError ? (
          <Alert tone="danger">{problemMessage(products.error.problem, t)}</Alert>
        ) : !products.data || products.data.content.length === 0 ? (
          <p className="text-mute">{t('catalog.products.noMatch')}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {products.data.content.map((p) => (
              <li key={p.id} className="flex items-center gap-3 rounded-ctl border border-line px-3 py-2 text-sm">
                <code>{p.sku}</code>
                <span>{p.commercialName}</span>
                <Button
                  type="button"
                  variant="secondary"
                  className="ms-auto"
                  onClick={() => {
                    onPick(p)
                    setQ('')
                  }}
                  aria-label={t('purchasing.pickProduct', { name: p.commercialName })}
                >
                  {t('stock.adjustment.pick')}
                </Button>
              </li>
            ))}
          </ul>
        ))}
    </div>
  )
}
