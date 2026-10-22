import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { problemMessage } from '../../lib/errors'
import { LotTable } from './LotsPage'
import { useProductLots } from './lotApi'

/** The Lots tab of a product (frontend.md §4.1): every lot in FEFO order, empty and unsellable ones included. */
export function ProductLotsPanel({ productId }: { productId: number }) {
  const { t } = useTranslation()
  const lots = useProductLots(productId)
  if (lots.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (lots.error instanceof ApiError) {
    return <Alert tone="danger">{problemMessage(lots.error.problem, t)}</Alert>
  }
  if (!lots.data || lots.data.length === 0) {
    return <p className="text-mute">{t('lots.empty')}</p>
  }
  return <LotTable lots={lots.data} showProduct={false} />
}
