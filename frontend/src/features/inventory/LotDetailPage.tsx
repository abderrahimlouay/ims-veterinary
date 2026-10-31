import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useLocation, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatDateTime, formatQuantity, formatUnitPrice } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { AlertBadge, LotStatusBadge } from './LotBadges'
import { useLot, useLotAction, useRecallReport, type LotAction, type LotDetail, type RecallReport } from './lotApi'
import { MovementsTable } from './MovementsTable'
import { useMovements } from './stockApi'

/** The stored status behind the effective one (ADR-059): only blocked and recalled are stored besides active. */
function storedStatus(lot: LotDetail): 'active' | 'blocked' | 'recalled' {
  return lot.status === 'blocked' || lot.status === 'recalled' ? lot.status : 'active'
}

/**
 * `/lots/:id` (frontend.md §4.2): effective status, balance and expiry; Block / Unblock / Recall with a mandatory
 * reason, offered only when the transition exists (ADR-060: never Unblock on a recalled lot) and the user holds the
 * permission; the recall report lists the buyers (BR-LOT-008). From Phase 4 the lot's movements (`stock.read`)
 * and a link to adjust it (`stock.adjust`, any status, ADR-085).
 */
export function LotDetailPage() {
  const { t } = useTranslation()
  const id = Number(useParams().id)
  const adjusted = (useLocation().state as { adjusted?: boolean } | null)?.adjusted === true
  const { data: current } = useCurrentUser()
  const lot = useLot(id)
  const [action, setAction] = useState<LotAction>()
  const [report, setReport] = useState<RecallReport>()
  const [showReport, setShowReport] = useState(false)
  const storedReport = useRecallReport(id, showReport && report === undefined)

  if (lot.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (lot.error instanceof ApiError || !lot.data) {
    return <Alert tone="danger">{lot.error instanceof ApiError ? problemMessage(lot.error.problem, t) : t('errors.UNKNOWN')}</Alert>
  }
  const data = lot.data
  const stored = storedStatus(data)
  const can = (code: string) => hasPermission(current, code)
  const shownReport = report ?? (showReport ? storedReport.data : undefined)

  return (
    <section className="flex flex-col gap-4 max-w-3xl">
      <div className="flex items-center gap-3 flex-wrap">
        <h1 className="text-2xl font-semibold">{t('lots.detailTitle', { lotNumber: data.lotNumber })}</h1>
        <LotStatusBadge status={data.status} />
        <AlertBadge level={data.alertLevel} />
      </div>
      {adjusted && <Alert tone="ok">{t('stock.adjustment.done')}</Alert>}
      <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
        <dt className="text-mute">{t('lots.product')}</dt>
        <dd>
          <Link to={`/products/${data.product.id}`} className="text-brand underline underline-offset-4">
            {data.product.commercialName}
          </Link>{' '}
          <code>{data.product.sku}</code>
        </dd>
        <dt className="text-mute">{t('lots.expiry')}</dt>
        <dd>
          {formatDate(data.expiryDate)} — {t('lots.daysLeftValue', { count: data.daysLeft })}
        </dd>
        <dt className="text-mute">{t('lots.quantity')}</dt>
        <dd>{`${formatQuantity(data.quantityOnHand)} ${data.product.baseUnit}`}</dd>
        <dt className="text-mute">{t('lots.unitCost')}</dt>
        <dd>{formatUnitPrice(data.unitCost)}</dd>
        <dt className="text-mute">{t('lots.receivedAt')}</dt>
        <dd>{formatDateTime(data.receivedAt)}</dd>
        <dt className="text-mute">{t('lots.sale')}</dt>
        <dd>{data.sellable ? t('lots.sellable') : t('lots.notSellable')}</dd>
        {data.blockedReason !== null && (
          <>
            <dt className="text-mute">{stored === 'recalled' ? t('lots.recallReason') : t('lots.blockReason')}</dt>
            <dd>{data.blockedReason}</dd>
          </>
        )}
        {data.blockedByName !== null && (
          <>
            <dt className="text-mute">{stored === 'recalled' ? t('lots.recalledBy') : t('lots.blockedBy')}</dt>
            <dd>{data.blockedByName}</dd>
          </>
        )}
        {data.blockedAt !== null && (
          <>
            <dt className="text-mute">{t('lots.blockedAt')}</dt>
            <dd>{formatDateTime(data.blockedAt)}</dd>
          </>
        )}
      </dl>
      <div className="flex gap-3">
        {stored === 'active' && can('lot.block') && <Button onClick={() => setAction('block')}>{t('lots.actions.block')}</Button>}
        {stored === 'blocked' && can('lot.unblock') && <Button onClick={() => setAction('unblock')}>{t('lots.actions.unblock')}</Button>}
        {stored !== 'recalled' && can('lot.recall') && (
          <Button variant="secondary" onClick={() => setAction('recall')}>
            {t('lots.actions.recall')}
          </Button>
        )}
        {can('stock.adjust') && (
          <Link
            to={`/stock/adjustments/new?lotId=${data.id}`}
            className="inline-flex items-center h-10 px-4 rounded-ctl text-sm font-medium bg-surface text-ink border border-control hover:bg-bg focus:outline-2 focus:outline-offset-2 focus:outline-brand"
          >
            {t('stock.adjustment.new')}
          </Link>
        )}
        {stored === 'recalled' && can('lot.recall') && shownReport === undefined && (
          <Button variant="secondary" onClick={() => setShowReport(true)}>
            {t('lots.actions.report')}
          </Button>
        )}
      </div>
      {storedReport.error instanceof ApiError && <Alert tone="danger">{problemMessage(storedReport.error.problem, t)}</Alert>}
      {shownReport && <RecallReportView report={shownReport} baseUnit={data.product.baseUnit} />}
      {action && (
        <ReasonDialog
          lot={data}
          action={action}
          onClose={() => setAction(undefined)}
          onRecalled={(r) => {
            setReport(r)
            setShowReport(true)
          }}
        />
      )}
      {can('stock.read') && <LotMovements lotId={data.id} />}
    </section>
  )
}

function LotMovements({ lotId }: { lotId: number }) {
  const { t } = useTranslation()
  const movements = useMovements({ productId: '', lotId: String(lotId), type: '', from: '', to: '', page: 0 })
  return (
    <section className="flex flex-col gap-2">
      <h2 className="font-semibold">{t('stock.movements.title')}</h2>
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
          <MovementsTable movements={movements.data.content} showLot={false} />
          {movements.data.page.totalPages > 1 && (
            <Link to={`/stock?view=movements&lotId=${lotId}`} className="text-sm text-brand underline underline-offset-4">
              {t('stock.movements.all')}
            </Link>
          )}
        </>
      )}
    </section>
  )
}

function ReasonDialog({ lot, action, onClose, onRecalled }: {
  lot: LotDetail
  action: LotAction
  onClose: () => void
  onRecalled: (report: RecallReport) => void
}) {
  const { t } = useTranslation()
  const mutation = useLotAction(lot.id)
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const problem = mutation.error instanceof ApiError ? mutation.error.problem : undefined

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    if (reason.trim() === '') {
      setMissing(true)
      return
    }
    mutation.mutate(
      { action, reason: reason.trim() },
      {
        onSuccess: (result) => {
          if (action === 'recall') {
            onRecalled(result as RecallReport)
          }
          onClose()
        },
      },
    )
  }

  return (
    <Dialog title={t(`lots.dialog.${action}`, { lotNumber: lot.lotNumber })} onClose={onClose}>
      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3">
        {action === 'recall' && <p className="text-sm text-mute">{t('lots.dialog.recallHint')}</p>}
        {problem && (
          <Alert tone="danger">
            {problem.code === 'INVALID_STATE_TRANSITION' ? t('lots.dialog.invalidTransition') : problemMessage(problem, t)}
          </Alert>
        )}
        <label className="flex flex-col gap-1 text-sm font-medium">
          {t('lots.dialog.reason')}
          <textarea
            value={reason}
            maxLength={500}
            aria-invalid={missing}
            onChange={(e) => {
              setReason(e.target.value)
              setMissing(false)
            }}
            className="min-h-20 px-3 py-2 rounded-ctl bg-surface border border-control text-sm font-normal focus:outline-2 focus:outline-brand aria-invalid:border-danger"
          />
        </label>
        {missing && <p className="text-sm text-danger">{t('validation.required')}</p>}
        <div className="flex gap-3 justify-end">
          <Button variant="secondary" onClick={onClose}>
            {t('lots.dialog.cancel')}
          </Button>
          <Button type="submit" disabled={mutation.isPending}>
            {t('lots.dialog.confirm')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/** api.md §5.7: who received the lot, anonymous sales included (BR-LOT-008). */
function RecallReportView({ report, baseUnit }: { report: RecallReport; baseUnit: string }) {
  const { t } = useTranslation()
  const none = report.buyers.length === 0 && report.anonymousSales.length === 0
  return (
    <section className="flex flex-col gap-2 border-t border-line pt-4">
      <h2 className="text-lg font-semibold">{t('lots.report.title')}</h2>
      <p className="text-sm">{t('lots.report.remaining', { quantity: `${formatQuantity(report.remainingStockBase)} ${baseUnit}` })}</p>
      {none ? (
        <p className="text-mute">{t('lots.report.noSales')}</p>
      ) : (
        <table className="text-sm border-collapse">
          <thead>
            <tr className="border-b border-line">
              <th className="text-start py-1 pe-4">{t('lots.report.customer')}</th>
              <th className="text-start pe-4">{t('lots.report.phone')}</th>
              <th className="text-start pe-4">{t('lots.report.document')}</th>
              <th className="text-start pe-4">{t('lots.report.date')}</th>
              <th className="text-end">{t('lots.quantity')}</th>
            </tr>
          </thead>
          <tbody>
            {report.buyers.map((b) => (
              <tr key={`${b.documentNumber}-${b.customerId}`} className="border-b border-line">
                <td className="py-1 pe-4">{b.customerName}</td>
                <td className="pe-4">{b.phone}</td>
                <td className="pe-4">{b.documentNumber}</td>
                <td className="pe-4">{formatDateTime(b.issuedAt)}</td>
                <td className="text-end">{formatQuantity(b.quantityBase)}</td>
              </tr>
            ))}
            {report.anonymousSales.map((s) => (
              <tr key={s.documentNumber} className="border-b border-line">
                <td className="py-1 pe-4">{t('lots.report.anonymous')}</td>
                <td className="pe-4" />
                <td className="pe-4">{s.documentNumber}</td>
                <td className="pe-4">{formatDateTime(s.issuedAt)}</td>
                <td className="text-end">{formatQuantity(s.quantityBase)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
