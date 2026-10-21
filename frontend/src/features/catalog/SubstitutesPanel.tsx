import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { problemMessage } from '../../lib/errors'
import { formatUnitPrice } from '../../lib/format'
import { useSubstitutes } from './productApi'

/** Products sharing an active ingredient, most shared first (FR-PROD-004); stock is shown from Phase 4. */
export function SubstitutesPanel({ productId }: { productId: number }) {
  const { t } = useTranslation()
  const substitutes = useSubstitutes(productId)
  if (substitutes.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (substitutes.error instanceof ApiError) {
    return <Alert tone="danger">{problemMessage(substitutes.error.problem, t)}</Alert>
  }
  if (!substitutes.data || substitutes.data.length === 0) {
    return <p className="text-mute">{t('catalog.substitutes.none')}</p>
  }
  return (
    <ul className="flex flex-col divide-y divide-line max-w-xl">
      {substitutes.data.map((p) => (
        <li key={p.id} className="flex items-center gap-3 py-2">
          <Link to={`/products/${p.id}`} className="text-brand underline underline-offset-4 flex-1">
            {p.commercialName}
          </Link>
          <code className="text-sm">{p.sku}</code>
          <span className="text-sm">{formatUnitPrice(p.salePrice)}</span>
        </li>
      ))}
    </ul>
  )
}
