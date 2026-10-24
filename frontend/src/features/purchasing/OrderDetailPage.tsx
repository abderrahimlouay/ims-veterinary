import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatDateTime, formatMoney, formatQuantity, formatUnitPrice } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { ACTIONS_BY_STATUS, useOrder, useOrderAction, type OrderAction, type PurchaseOrderDetail } from './orderApi'

/**
 * `/purchase-orders/:id` (frontend.md §4.5): lines with the server's amounts per line and totals (ADR-093), received
 * and outstanding quantities, the status timeline, the receipts, and the actions of the status (BR-PURCH-001):
 * validate, send, cancel (draft/validated/sent) and close a partially received order (ADR-096) — cancel and close
 * ask for a reason. A draft can be edited (BR-PURCH-003).
 */
export function OrderDetailPage() {
  const { t } = useTranslation()
  const id = Number(useParams().id)
  const { data: current } = useCurrentUser()
  const order = useOrder(id)
  const action = useOrderAction(id)
  const [asking, setAsking] = useState<OrderAction>()

  if (order.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (order.error instanceof ApiError || !order.data) {
    return <Alert tone="danger">{order.error instanceof ApiError ? problemMessage(order.error.problem, t) : t('errors.UNKNOWN')}</Alert>
  }
  const o = order.data
  const actions = hasPermission(current, 'purchase.validate') ? ACTIONS_BY_STATUS[o.status] : []
  const editable = o.status === 'draft' && hasPermission(current, 'purchase.create')

  return (
    <section className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center gap-4">
        <h1 className="text-2xl font-semibold">{o.number}</h1>
        <span className="rounded-ctl border border-line px-2 py-1 text-sm">{t(`purchasing.orders.statuses.${o.status}`)}</span>
        <div className="ms-auto flex gap-2">
          {editable && (
            <Link to={`/purchase-orders/${o.id}/edit`} className="h-10 px-4 inline-flex items-center rounded-ctl border border-control text-sm font-medium">
              {t('purchasing.orders.editButton')}
            </Link>
          )}
          {actions.map((a) => (
            <Button
              key={a}
              variant={a === 'cancel' || a === 'close' ? 'secondary' : 'primary'}
              disabled={action.isPending}
              onClick={() => (a === 'cancel' || a === 'close' ? setAsking(a) : action.mutate({ action: a }))}
            >
              {t(`purchasing.orders.actions.${a}`)}
            </Button>
          ))}
        </div>
      </div>
      {action.error instanceof ApiError && asking === undefined && <Alert tone="danger">{problemMessage(action.error.problem, t)}</Alert>}
      <dl className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-sm">
        <dt className="text-mute">{t('purchasing.orders.supplier')}</dt>
        <dd>
          <Link to={`/suppliers/${o.supplier.id}`} className="underline">
            {o.supplier.name}
          </Link>
        </dd>
        <dt className="text-mute">{t('purchasing.orders.orderDate')}</dt>
        <dd>{formatDate(o.orderDate)}</dd>
        <dt className="text-mute">{t('purchasing.orders.expectedDate')}</dt>
        <dd>{o.expectedDate ? formatDate(o.expectedDate) : '—'}</dd>
      </dl>
      <LinesTable order={o} />
      <Timeline order={o} />
      {o.receipts.length > 0 && (
        <div className="flex flex-col gap-2">
          <h2 className="text-lg font-semibold">{t('purchasing.orders.receipts')}</h2>
          <ul className="text-sm">
            {o.receipts.map((r) => (
              <li key={r.id}>
                <Link to={`/goods-receipts/${r.id}`} className="underline">
                  {r.number}
                </Link>{' '}
                — {formatDateTime(r.receivedAt)} — {t(`purchasing.receipts.statuses.${r.status}`)}
              </li>
            ))}
          </ul>
        </div>
      )}
      {asking && (
        <ReasonDialog
          title={t(`purchasing.orders.actions.${asking}`)}
          pending={action.isPending}
          error={action.error instanceof ApiError ? action.error : undefined}
          onClose={() => {
            setAsking(undefined)
            action.reset()
          }}
          onConfirm={(reason) => action.mutate({ action: asking, reason }, { onSuccess: () => setAsking(undefined) })}
        />
      )}
    </section>
  )
}

function LinesTable({ order }: { order: PurchaseOrderDetail }) {
  const { t } = useTranslation()
  return (
    <table className="w-full text-sm border-collapse">
      <thead>
        <tr className="border-b border-line">
          <th className="text-start py-2">{t('catalog.products.name')}</th>
          <th className="text-end">{t('purchasing.orders.quantity')}</th>
          <th className="text-end">{t('purchasing.orders.unitPrice')}</th>
          <th className="text-end">{t('purchasing.orders.discountPct')}</th>
          <th className="text-end">{t('purchasing.orders.vatRate')}</th>
          <th className="text-end">{t('purchasing.orders.lineHt')}</th>
          <th className="text-end">{t('purchasing.orders.lineVat')}</th>
          <th className="text-end">{t('purchasing.orders.received')}</th>
          <th className="text-end">{t('purchasing.orders.outstanding')}</th>
        </tr>
      </thead>
      <tbody>
        {order.lines.map((l) => (
          <tr key={l.id} className="border-b border-line">
            <td className="py-2">{l.product.commercialName}</td>
            <td className="text-end">{`${formatQuantity(l.quantity)} ${l.unit?.name ?? l.product.baseUnit}`}</td>
            <td className="text-end">{formatUnitPrice(l.unitPrice)}</td>
            <td className="text-end">{l.discountPct} %</td>
            <td className="text-end">{l.vatRate} %</td>
            <td className="text-end">{formatMoney(l.lineHt)}</td>
            <td className="text-end">{formatMoney(l.lineVat)}</td>
            <td className="text-end">{formatQuantity(l.receivedBase)}</td>
            <td className="text-end">{`${formatQuantity(l.outstandingBase)} ${l.product.baseUnit}`}</td>
          </tr>
        ))}
      </tbody>
      <tfoot>
        <tr>
          <td colSpan={5} className="py-2 text-end font-medium">
            {t('purchasing.orders.totals')}
          </td>
          <td className="text-end">{formatMoney(order.totalHt)}</td>
          <td className="text-end">{formatMoney(order.totalVat)}</td>
          <td colSpan={2} className="text-end font-semibold">
            {t('purchasing.orders.totalTtcValue', { amount: formatMoney(order.totalTtc) })}
          </td>
        </tr>
      </tfoot>
    </table>
  )
}

function Timeline({ order }: { order: PurchaseOrderDetail }) {
  const { t } = useTranslation()
  const steps: { label: string; at: string | null; detail?: string }[] = [
    { label: t('purchasing.orders.timeline.created', { by: order.createdBy.fullName }), at: order.createdAt },
    { label: t('purchasing.orders.timeline.validated'), at: order.validatedAt },
    { label: t('purchasing.orders.timeline.sent'), at: order.sentAt },
    { label: t('purchasing.orders.timeline.cancelled', { by: order.cancelledBy?.fullName ?? '—' }), at: order.cancelledAt, detail: order.cancelReason ?? undefined },
    { label: t('purchasing.orders.timeline.closed'), at: order.closedAt, detail: order.closedReason ?? undefined },
  ]
  return (
    <ol className="flex flex-col gap-1 text-sm">
      {steps
        .filter((s) => s.at !== null)
        .map((s) => (
          <li key={s.label}>
            <span className="text-mute">{formatDateTime(s.at as string)}</span> — {s.label}
            {s.detail && ` : ${s.detail}`}
          </li>
        ))}
    </ol>
  )
}

/** Asks for the mandatory reason of a cancellation or a forced close; the server's refusal stays visible. */
export function ReasonDialog({ title, pending, error, onClose, onConfirm }: {
  title: string
  pending: boolean
  error?: ApiError
  onClose: () => void
  onConfirm: (reason: string) => void
}) {
  const { t } = useTranslation()
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (reason.trim() === '') {
      setMissing(true)
      return
    }
    onConfirm(reason.trim())
  }
  return (
    <Dialog title={title} onClose={onClose}>
      <form onSubmit={submit} noValidate className="flex flex-col gap-4">
        {error && error.problem.errors.length === 0 && <Alert tone="danger">{problemMessage(error.problem, t)}</Alert>}
        <TextField label={t('purchasing.reason')} value={reason} error={missing ? t('fieldErrors.REQUIRED') : undefined} onChange={(e) => setReason(e.target.value)} />
        <div className="flex gap-2">
          <Button type="submit" disabled={pending}>
            {t('purchasing.confirm')}
          </Button>
          <Button type="button" variant="secondary" onClick={onClose}>
            {t('purchasing.close')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
