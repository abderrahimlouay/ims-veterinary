import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatDateTime, formatQuantity, formatUnitPrice } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { ReasonDialog } from './OrderDetailPage'
import { useCancelReceipt, useReceipt } from './receiptApi'

/**
 * `/goods-receipts/:id` (frontend.md §4.5): lines with their lots and movements; a cancelled receipt is shown as
 * cancelled with its reason. "Cancel receipt" (`receipt.cancel`) asks for a reason; a refusal names the lot and the
 * later movement (ADR-103).
 */
export function ReceiptDetailPage() {
  const { t } = useTranslation()
  const id = Number(useParams().id)
  const { data: current } = useCurrentUser()
  const receipt = useReceipt(id)
  const cancel = useCancelReceipt(id)
  const [asking, setAsking] = useState(false)

  if (receipt.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (receipt.error instanceof ApiError || !receipt.data) {
    return <Alert tone="danger">{receipt.error instanceof ApiError ? problemMessage(receipt.error.problem, t) : t('errors.UNKNOWN')}</Alert>
  }
  const r = receipt.data
  const refusal = cancel.error instanceof ApiError ? cancel.error.problem : undefined
  return (
    <section className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center gap-4">
        <h1 className="text-2xl font-semibold">{r.number}</h1>
        <span className="rounded-ctl border border-line px-2 py-1 text-sm">{t(`purchasing.receipts.statuses.${r.status}`)}</span>
        {r.status === 'confirmed' && hasPermission(current, 'receipt.cancel') && (
          <Button variant="secondary" className="ms-auto" onClick={() => setAsking(true)}>
            {t('purchasing.receipts.cancel')}
          </Button>
        )}
      </div>
      {refusal && !asking && <Alert tone="danger">{refusalMessage(refusal, t)}</Alert>}
      {r.status === 'cancelled' && (
        <Alert tone="danger">
          {t('purchasing.receipts.cancelledBy', { by: r.cancelledBy?.fullName ?? '—', at: r.cancelledAt ? formatDateTime(r.cancelledAt) : '—', reason: r.cancelReason ?? '' })}
        </Alert>
      )}
      <dl className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-sm">
        <dt className="text-mute">{t('purchasing.orders.supplier')}</dt>
        <dd>
          <Link to={`/suppliers/${r.supplier.id}`} className="underline">
            {r.supplier.name}
          </Link>
        </dd>
        <dt className="text-mute">{t('purchasing.receipts.order')}</dt>
        <dd>
          {r.purchaseOrder ? (
            <Link to={`/purchase-orders/${r.purchaseOrder.id}`} className="underline">
              {r.purchaseOrder.number}
            </Link>
          ) : (
            t('purchasing.receipts.free')
          )}
        </dd>
        <dt className="text-mute">{t('purchasing.receipts.receivedAt')}</dt>
        <dd>{t('purchasing.receipts.receivedBy', { at: formatDateTime(r.receivedAt), by: r.receivedBy.fullName })}</dd>
        <dt className="text-mute">{t('purchasing.receipts.docRef')}</dt>
        <dd>{r.supplierDocRef ?? '—'}</dd>
      </dl>
      <table className="w-full text-sm border-collapse">
        <thead>
          <tr className="border-b border-line">
            <th className="text-start py-2">{t('catalog.products.name')}</th>
            <th className="text-start">{t('purchasing.receipts.lotNumber')}</th>
            <th className="text-start">{t('purchasing.receipts.expiry')}</th>
            <th className="text-end">{t('purchasing.orders.quantity')}</th>
            <th className="text-end">{t('purchasing.receipts.unitCost')}</th>
            <th className="text-start ps-4">{t('purchasing.receipts.conformity')}</th>
          </tr>
        </thead>
        <tbody>
          {r.lines.map((l) => (
            <tr key={l.id} className="border-b border-line">
              <td className="py-2">{l.product.commercialName}</td>
              <td>
                <Link to={`/lots/${l.lotId}`} className="underline">
                  {l.lotNumber}
                </Link>
              </td>
              <td>{formatDate(l.expiryDate)}</td>
              <td className="text-end">{`${formatQuantity(l.quantityBase)} ${l.product.baseUnit}`}</td>
              <td className="text-end">{formatUnitPrice(l.unitCost)}</td>
              <td className="ps-4">
                {l.conforming ? t('purchasing.receipts.conforming') : t('purchasing.receipts.nonConforming')}
                {l.lotBlocked && ` — ${t('purchasing.receipts.lotBlocked')}`}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {asking && (
        <ReasonDialog
          title={t('purchasing.receipts.cancel')}
          pending={cancel.isPending}
          error={cancel.error instanceof ApiError && cancel.error.problem.code !== 'RECEIPT_NOT_CANCELLABLE' ? cancel.error : undefined}
          onClose={() => setAsking(false)}
          onConfirm={(reason) => cancel.mutate(reason, { onSettled: () => setAsking(false) })}
        />
      )}
    </section>
  )
}

/** ADR-103: a refused cancellation names the lot and the movement that came after the receipt. */
function refusalMessage(problem: Problem, t: (key: string, options?: Record<string, unknown>) => string): string {
  if (problem.code !== 'RECEIPT_NOT_CANCELLABLE') {
    return problemMessage(problem, t as never)
  }
  const c = problem.context
  return t('purchasing.receipts.notCancellable', {
    lotNumber: c.lotNumber,
    movement: t(`stock.movementType.${String(c.movementType)}`, { defaultValue: String(c.movementType) }),
    at: typeof c.movementAt === 'string' ? formatDateTime(c.movementAt) : '—',
  })
}
