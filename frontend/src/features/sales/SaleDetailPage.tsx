import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatDateTime, formatMoney, formatQuantity, formatUnitPrice } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { ReasonDialog } from '../purchasing'
import { useAbandonSale, useCancelSale, useConvertSale, useSale, type DocType, type SaleDetail } from './saleApi'

/** BR-SALE-001 and W11: what the server would accept; it decides anyway. */
function conversions(sale: SaleDetail): DocType[] {
  if (sale.docType === 'quote' && sale.status !== 'cancelled') {
    return ['delivery_note', 'invoice']
  }
  return sale.docType === 'delivery_note' && sale.status === 'confirmed' ? ['invoice'] : []
}

/**
 * `/sales/:id` (frontend.md §4.8): lines, totals, payments and the parent document. A derived draft and any other draft
 * are finished at the counter. Cancelling is whole, with a reason (ADR-119); a refusal says why. PDF buttons: Phase 10.
 */
export function SaleDetailPage() {
  const { t } = useTranslation()
  const id = Number(useParams().id)
  const navigate = useNavigate()
  const { data: current } = useCurrentUser()
  const sale = useSale(id)
  const convert = useConvertSale(id)
  const cancel = useCancelSale(id)
  const abandon = useAbandonSale(id)
  const [cancelling, setCancelling] = useState(false)

  if (sale.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (sale.error instanceof ApiError) {
    return <Alert tone="danger">{problemMessage(sale.error.problem, t)}</Alert>
  }
  if (!sale.data) {
    return null
  }
  const data = sale.data
  const canSell = hasPermission(current, 'sale.create')
  const failure = convert.error ?? abandon.error
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{data.number ?? t('sales.draftOf', { id: data.id })}</h1>
      {failure instanceof ApiError && <Alert tone="danger">{problemMessage(failure.problem, t)}</Alert>}
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm max-w-xl">
        <dt>{t('sales.type')}</dt>
        <dd>
          {t(`pos.types.${data.docType}`, { defaultValue: data.docType })}
          {data.creditSale && ` · ${t('sales.onCredit')}`}
        </dd>
        <dt>{t('sales.status')}</dt>
        <dd data-testid="sale-status">{t(`sales.statuses.${data.status}`)}</dd>
        <dt>{t('sales.customer')}</dt>
        <dd>{data.customer ? <Link to={`/customers/${data.customer.id}`} className="text-brand underline underline-offset-4">{data.customer.name}</Link> : t('sales.anonymous')}</dd>
        {data.issuedAt && (
          <>
            <dt>{t('sales.date')}</dt>
            <dd>{formatDateTime(data.issuedAt)}</dd>
          </>
        )}
        {data.dueDate && (
          <>
            <dt>{t('sales.dueDate')}</dt>
            <dd>{formatDate(data.dueDate)}</dd>
          </>
        )}
        {data.parentId !== null && (
          <>
            <dt>{t('sales.parent')}</dt>
            <dd>
              <Link to={`/sales/${data.parentId}`} className="text-brand underline underline-offset-4">
                {t('sales.parentLink')}
              </Link>
            </dd>
          </>
        )}
        {data.cancelReason && (
          <>
            <dt>{t('sales.cancelReason')}</dt>
            <dd>{data.cancelReason}</dd>
          </>
        )}
      </dl>
      <table className="w-full text-sm border-collapse">
        <thead>
          <tr className="border-b border-line">
            <th className="text-start py-2">{t('sales.product')}</th>
            <th className="text-start">{t('sales.lot')}</th>
            <th className="text-end">{t('pos.quantity')}</th>
            <th className="text-end">{t('sales.unitPrice')}</th>
            <th className="text-end">{t('pos.discount')}</th>
            <th className="text-end">{t('sales.total')}</th>
          </tr>
        </thead>
        <tbody>
          {data.lines.map((line) => (
            <tr key={line.id} className="border-b border-line">
              <td className="py-2">{line.productName}</td>
              <td>{line.lotNumber !== null && line.expiryDate !== null ? `${line.lotNumber} · ${formatDate(line.expiryDate)}` : '—'}</td>
              <td className="text-end">
                {formatQuantity(line.quantity)} {line.unitName}
              </td>
              <td className="text-end">{formatUnitPrice(line.unitPrice)}</td>
              <td className="text-end">{line.discountPct} %</td>
              <td className="text-end">{formatMoney(line.lineTtc)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 text-sm max-w-xs ms-auto">
        <dt>{t('pos.ht')}</dt>
        <dd className="text-end">{formatMoney(data.totals.ht)}</dd>
        <dt>{t('pos.vat')}</dt>
        <dd className="text-end">{formatMoney(data.totals.vat)}</dd>
        <dt className="font-semibold">{t('pos.ttc')}</dt>
        <dd className="text-end font-semibold">{formatMoney(data.totals.ttc)}</dd>
      </dl>
      {data.payments.length > 0 && (
        <section className="flex flex-col gap-1 text-sm">
          <h2 className="text-lg font-semibold">{t('pos.payment')}</h2>
          <ul>
            {data.payments.map((p) => (
              <li key={p.id}>
                {t(`pos.methods.${p.method}`)} · {formatMoney(p.amount)}
                {p.reference && ` · ${p.reference}`}
              </li>
            ))}
          </ul>
        </section>
      )}
      <div className="flex flex-wrap gap-2">
        {canSell && data.status === 'draft' && (
          <>
            <Link to={`/pos?draft=${data.id}`} className="h-10 px-4 inline-flex items-center rounded-ctl bg-brand text-brand-ink text-sm font-medium">
              {t('sales.resume')}
            </Link>
            <Button type="button" variant="secondary" disabled={abandon.isPending} onClick={() => abandon.mutate(undefined, { onSuccess: () => void sale.refetch() })}>
              {t('pos.abandon')}
            </Button>
          </>
        )}
        {canSell &&
          conversions(data).map((target) => (
            <Button key={target} type="button" variant="secondary" disabled={convert.isPending} onClick={() => convert.mutate(target, { onSuccess: (derived) => void navigate(`/pos?draft=${derived.id}`) })}>
              {t('sales.convertTo', { type: t(`pos.types.${target}`) })}
            </Button>
          ))}
        {hasPermission(current, 'sale.cancel') && data.status === 'confirmed' && (
          <Button type="button" variant="secondary" onClick={() => setCancelling(true)}>
            {t('sales.cancel')}
          </Button>
        )}
      </div>
      {cancelling && (
        <ReasonDialog
          title={t('sales.cancelTitle')}
          pending={cancel.isPending}
          error={cancel.error instanceof ApiError ? cancel.error : undefined}
          onClose={() => {
            setCancelling(false)
            cancel.reset()
          }}
          onConfirm={(reason) => cancel.mutate(reason, { onSuccess: () => setCancelling(false) })}
        />
      )}
    </section>
  )
}
