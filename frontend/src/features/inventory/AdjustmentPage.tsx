import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { isNegativeDecimal, subtractDecimal } from '../../lib/decimal'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatQuantity } from '../../lib/format'
import { LotStatusBadge } from './LotBadges'
import { useLot, useLots, type LotSummary } from './lotApi'
import { ADJUSTMENT_TYPES, useAdjustment, type AdjustmentType } from './stockApi'

const REASON_MAX = 500

type FieldErrors = Partial<Record<'type' | 'quantity' | 'reason', string>>

/**
 * `/stock/adjustments/new` (frontend.md §4.3, ADR-085): a lot (any status: blocked, recalled and expired lots
 * included, it is how unsellable stock leaves), a type, a quantity in base units and a mandatory reason; the
 * "balance after" is a preview only, the server computes and checks it (BR-STOCK-001). `?lotId=` preselects the lot.
 */
export function AdjustmentPage() {
  const { t } = useTranslation()
  const [params, setParams] = useSearchParams()
  const lotId = Number(params.get('lotId') ?? '0')
  return (
    <section className="flex flex-col gap-4 max-w-2xl">
      <h1 className="text-2xl font-semibold">{t('stock.adjustment.title')}</h1>
      {lotId > 0 ? (
        <AdjustmentForm lotId={lotId} onChangeLot={() => setParams({}, { replace: true })} />
      ) : (
        <LotPicker onPick={(lot) => setParams({ lotId: String(lot.id) }, { replace: true })} />
      )}
    </section>
  )
}

function LotPicker({ onPick }: { onPick: (lot: LotSummary) => void }) {
  const { t } = useTranslation()
  const [q, setQ] = useState('')
  const lots = useLots({ productId: '', status: '', alertLevel: '', expiringWithinDays: '', hasStock: true, q, page: 0 })
  return (
    <div className="flex flex-col gap-3">
      <TextField label={t('stock.adjustment.findLot')} type="search" value={q} onChange={(e) => setQ(e.target.value)} />
      {lots.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : lots.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(lots.error.problem, t)}</Alert>
      ) : !lots.data || lots.data.content.length === 0 ? (
        <p className="text-mute">{t('stock.adjustment.noLot')}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {lots.data.content.map((lot) => (
            <li key={lot.id} className="flex items-center gap-3 rounded-ctl border border-line px-3 py-2 text-sm">
              <span className="font-medium">{lot.lotNumber}</span>
              <span>{lot.product.commercialName}</span>
              <span className="text-mute">{formatDate(lot.expiryDate)}</span>
              <span>{`${formatQuantity(lot.quantityOnHand)} ${lot.product.baseUnit}`}</span>
              <LotStatusBadge status={lot.status} />
              <Button variant="secondary" className="ms-auto" onClick={() => onPick(lot)} aria-label={t('stock.adjustment.pickLot', { lotNumber: lot.lotNumber })}>
                {t('stock.adjustment.pick')}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function AdjustmentForm({ lotId, onChangeLot }: { lotId: number; onChangeLot: () => void }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const lot = useLot(lotId)
  const adjust = useAdjustment()
  const [type, setType] = useState<AdjustmentType>('adjustment_breakage')
  const [quantity, setQuantity] = useState('')
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<FieldErrors>({})
  const [problem, setProblem] = useState<Problem>()

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
  const after = quantity.trim() === '' ? null : subtractDecimal(data.quantityOnHand, quantity)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const local: FieldErrors = {}
    if (quantity.trim() === '') {
      local.quantity = t('fieldErrors.REQUIRED')
    }
    if (reason.trim() === '') {
      local.reason = t('fieldErrors.REQUIRED')
    }
    setErrors(local)
    setProblem(undefined)
    if (Object.keys(local).length > 0) {
      return
    }
    adjust.mutate(
      { lotId, type, quantity: quantity.trim().replace(',', '.'), reason: reason.trim() },
      {
        onSuccess: () => void navigate(`/lots/${lotId}`, { state: { adjusted: true } }),
        onError: (error) => {
          if (!(error instanceof ApiError)) {
            return
          }
          const server: FieldErrors = {}
          for (const e of error.problem.errors) {
            if (e.field === 'type' || e.field === 'quantity' || e.field === 'reason') {
              server[e.field] = t(`fieldErrors.${e.code}`)
            }
          }
          if (error.problem.code === 'ADJUSTMENT_REASON_REQUIRED') {
            server.reason = t('fieldErrors.REQUIRED')
          }
          setErrors(server)
          if (Object.keys(server).length === 0) {
            setProblem(error.problem)
          }
        },
      },
    )
  }

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-4">
      <div className="flex items-center gap-3 flex-wrap rounded-ctl border border-line p-3 text-sm">
        <Link to={`/lots/${data.id}`} className="font-medium text-brand underline underline-offset-4">
          {data.lotNumber}
        </Link>
        <span>{data.product.commercialName}</span>
        <span className="text-mute">{formatDate(data.expiryDate)}</span>
        <LotStatusBadge status={data.status} />
        <span>{t('stock.adjustment.balance', { quantity: `${formatQuantity(data.quantityOnHand)} ${data.product.baseUnit}` })}</span>
        <button type="button" className="ms-auto underline text-brand" onClick={onChangeLot}>
          {t('stock.adjustment.changeLot')}
        </button>
      </div>
      <SelectField
        label={t('stock.adjustment.type')}
        value={type}
        error={errors.type}
        onChange={(e) => setType(e.target.value as AdjustmentType)}
        options={ADJUSTMENT_TYPES.map((value) => ({ value, label: t(`stock.movementType.${value}`) }))}
      />
      <TextField
        label={t('stock.adjustment.quantity', { unit: data.product.baseUnit })}
        inputMode="decimal"
        value={quantity}
        error={errors.quantity}
        hint={after === null ? undefined : t('stock.adjustment.after', { quantity: `${formatQuantity(after)} ${data.product.baseUnit}` })}
        onChange={(e) => setQuantity(e.target.value)}
      />
      {after !== null && isNegativeDecimal(after) && <Alert tone="danger">{t('stock.adjustment.tooMuch')}</Alert>}
      <TextField
        label={t('stock.adjustment.reason')}
        value={reason}
        maxLength={REASON_MAX}
        error={errors.reason}
        onChange={(e) => setReason(e.target.value)}
      />
      {problem && (
        <Alert tone="danger">
          {problem.code === 'STOCK_INSUFFICIENT' && typeof problem.context.available === 'string'
            ? t('stock.adjustment.insufficient', { quantity: `${formatQuantity(problem.context.available)} ${data.product.baseUnit}` })
            : problemMessage(problem, t)}
        </Alert>
      )}
      <div>
        <Button type="submit" disabled={adjust.isPending}>
          {t('stock.adjustment.submit')}
        </Button>
      </div>
    </form>
  )
}
