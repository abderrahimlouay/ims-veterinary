import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { TextField } from '../../components/TextField'
import { isNegativeDecimal, subtractDecimal, sumDecimals } from '../../lib/decimal'
import { problemMessage } from '../../lib/errors'
import { parseExpiry } from '../../lib/expiry'
import { formatDate, formatDateTime, formatQuantity } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { useProducts, type ProductSummary } from '../catalog'
import { CountStatusBadge } from './CountBadges'
import {
  useAddLine,
  useCancelCount,
  useCount,
  useEnterCounted,
  useRegularize,
  type CountDetail,
  type CountLine,
} from './countApi'
import { useLots, type LotSummary } from './lotApi'

/** A signed quantity with its sign, so a direction never depends on colour alone. */
function signed(value: string): string {
  const negative = isNegativeDecimal(value)
  const zero = !/[1-9]/.test(value)
  return `${zero ? '' : negative ? '−' : '+'}${formatQuantity(value.replace('-', ''))}`
}

function lineLabel(line: CountLine): string {
  const lot = line.lot ?? line.foundLot
  return `${line.product.commercialName} — ${lot?.lotNumber ?? ''}`
}

/**
 * `/inventory-counts/:id` (frontend.md §4.4, W5): the entry grid. Saving a counted cell (Enter or leaving it) takes
 * a new watermark (ADR-072) and Enter moves to the next cell; the variance is counted − balance when counted, and
 * the movements since then are shown beside it. "Add a lot" for a lot of the scope without a line or a lot unknown
 * to the system (ADR-074). "Regularise" (`inventory.validate`) confirms the net quantity per product; a refusal names
 * the line and keeps the count open (ADR-075).
 */
export function CountPage() {
  const { t } = useTranslation()
  const id = Number(useParams().id)
  const { data: current } = useCurrentUser()
  const count = useCount(id)
  const [dialog, setDialog] = useState<'add' | 'regularize' | 'cancel'>()
  const [failedLine, setFailedLine] = useState<number>()

  if (count.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (count.error instanceof ApiError || !count.data) {
    return <Alert tone="danger">{count.error instanceof ApiError ? problemMessage(count.error.problem, t) : t('errors.UNKNOWN')}</Alert>
  }
  const data = count.data
  const open = data.status === 'open'
  const canValidate = hasPermission(current, 'inventory.validate')

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-3 flex-wrap">
        <h1 className="text-2xl font-semibold">{t('counts.label', { id: data.id })}</h1>
        <span>{t(`counts.types.${data.type}`)}</span>
        <CountStatusBadge status={data.status} />
        {open && (
          <div className="ms-auto flex gap-3">
            <Button variant="secondary" onClick={() => setDialog('add')}>
              {t('counts.addLot')}
            </Button>
            <Button disabled={!canValidate} title={canValidate ? undefined : t('counts.validateOnly')} onClick={() => setDialog('regularize')}>
              {t('counts.regularize')}
            </Button>
            {canValidate && (
              <Button variant="secondary" onClick={() => setDialog('cancel')}>
                {t('counts.cancel')}
              </Button>
            )}
          </div>
        )}
      </div>
      <p className="text-sm text-mute">
        {t('counts.startedLine', { at: formatDateTime(data.startedAt), by: data.startedBy.fullName })}
        {data.closedAt !== null && data.closedBy !== null && (
          <> · {t('counts.closedLine', { at: formatDateTime(data.closedAt), by: data.closedBy.fullName })}</>
        )}
        {data.notes !== null && <> · {data.notes}</>}
      </p>
      {data.lines.length === 0 ? (
        <p className="text-mute">{t('counts.noLines')}</p>
      ) : (
        <CountGrid count={data} failedLine={failedLine} />
      )}
      {dialog === 'add' && <AddLotDialog countId={data.id} onClose={() => setDialog(undefined)} />}
      {dialog === 'regularize' && (
        <RegularizeDialog count={data} onClose={() => setDialog(undefined)} onRefused={setFailedLine} />
      )}
      {dialog === 'cancel' && <CancelDialog countId={data.id} onClose={() => setDialog(undefined)} />}
    </section>
  )
}

function CountGrid({ count, failedLine }: { count: CountDetail; failedLine?: number }) {
  const { t } = useTranslation()
  const enter = useEnterCounted(count.id)
  const [errors, setErrors] = useState<Record<number, string>>({})
  const inputs = useRef(new Map<number, HTMLInputElement>())
  const editable = count.lines.filter((l) => count.status === 'open' && !l.regularized)

  const save = (line: CountLine, draft: string) => {
    const value = draft.trim().replace(',', '.')
    if (value === '' || (line.countedQty !== null && subtractDecimal(value, line.countedQty) === '0.000')) {
      return
    }
    enter.mutate(
      { lineId: line.id, countedQty: value },
      {
        onSuccess: () =>
          setErrors((all) => {
            const rest = { ...all }
            delete rest[line.id]
            return rest
          }),
        onError: (error) => {
          if (error instanceof ApiError) {
            const field = error.problem.errors.find((e) => e.field === 'countedQty')
            setErrors((e) => ({ ...e, [line.id]: field ? t(`fieldErrors.${field.code}`) : problemMessage(error.problem, t) }))
          }
        },
      },
    )
  }

  const next = (line: CountLine) => {
    const index = editable.findIndex((l) => l.id === line.id)
    const following = editable[index + 1]
    if (following) {
      inputs.current.get(following.id)?.focus()
    }
  }

  return (
    <table className="w-full text-sm border-collapse">
      <thead>
        <tr className="border-b border-line">
          <th className="text-start py-2">{t('counts.grid.product')}</th>
          <th className="text-start">{t('counts.grid.lot')}</th>
          <th className="text-end">{t('counts.grid.theoretical')}</th>
          <th className="text-end">{t('counts.grid.counted')}</th>
          <th className="text-end">{t('counts.grid.balanceAtCount')}</th>
          <th className="text-end">{t('counts.grid.variance')}</th>
          <th className="text-end">{t('counts.grid.since')}</th>
          <th className="text-end">{t('counts.grid.current')}</th>
          <th className="text-start ps-4">{t('counts.grid.status')}</th>
        </tr>
      </thead>
      <tbody>
        {count.lines.map((line) => {
          const varying = line.varianceQty !== null && /[1-9]/.test(line.varianceQty)
          return (
            <tr key={line.id} className={`border-b border-line align-top ${failedLine === line.id ? 'bg-danger/10' : ''}`}>
              <td className="py-2">
                {line.product.commercialName} <code className="text-mute">{line.product.sku}</code>
              </td>
              <td>
                {line.lot ? (
                  <Link to={`/lots/${line.lot.id}`} className="text-brand underline underline-offset-4">
                    {line.lot.lotNumber}
                  </Link>
                ) : (
                  <span>{t('counts.foundLot', { lotNumber: line.foundLot?.lotNumber })}</span>
                )}{' '}
                <span className="text-mute">{formatDate((line.lot ?? line.foundLot)?.expiryDate ?? '')}</span>
              </td>
              <td className="text-end">{formatQuantity(line.theoreticalQty)}</td>
              <td className="text-end">
                {count.status === 'open' && !line.regularized ? (
                  <CountedInput
                    line={line}
                    error={errors[line.id]}
                    register={(el) => (el ? inputs.current.set(line.id, el) : inputs.current.delete(line.id))}
                    onSave={(draft) => save(line, draft)}
                    onNext={() => next(line)}
                  />
                ) : line.countedQty !== null ? (
                  formatQuantity(line.countedQty)
                ) : (
                  '—'
                )}
              </td>
              <td className="text-end">{line.balanceAtCount !== null ? formatQuantity(line.balanceAtCount) : '—'}</td>
              <td className={`text-end ${varying ? 'font-semibold text-danger' : ''}`}>
                {line.varianceQty !== null ? signed(line.varianceQty) : '—'}
              </td>
              <td className="text-end">{line.movementsSinceCount !== null ? signed(line.movementsSinceCount) : '—'}</td>
              <td className="text-end">{line.currentBalance !== null ? formatQuantity(line.currentBalance) : '—'}</td>
              <td className="ps-4">
                {line.regularized
                  ? t('counts.lineStatus.regularized')
                  : line.countedQty !== null
                    ? t('counts.lineStatus.counted', { by: line.countedBy?.fullName ?? '' })
                    : t('counts.lineStatus.toCount')}
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

function CountedInput({ line, error, register, onSave, onNext }: {
  line: CountLine
  error?: string
  register: (el: HTMLInputElement | null) => void
  onSave: (draft: string) => void
  onNext: () => void
}) {
  const { t } = useTranslation()
  const [draft, setDraft] = useState(line.countedQty ?? '')
  /** Enter then the blur it causes would save twice; a value is sent again only after an error. */
  const sent = useRef(line.countedQty ?? '')
  const save = () => {
    if (draft.trim() === sent.current && error === undefined) {
      return
    }
    sent.current = draft.trim()
    onSave(draft)
  }
  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      save()
      onNext()
    }
  }
  return (
    <div className="flex flex-col items-end gap-1">
      <input
        ref={register}
        aria-label={t('counts.grid.countedFor', { line: lineLabel(line) })}
        aria-invalid={error !== undefined}
        inputMode="decimal"
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={save}
        onKeyDown={onKeyDown}
        className="h-9 w-24 px-2 text-end rounded-ctl bg-surface border border-control focus:outline-2 focus:outline-brand aria-invalid:border-danger"
      />
      {error !== undefined && <span className="text-xs text-danger">{error}</span>}
    </div>
  )
}

function AddLotDialog({ countId, onClose }: { countId: number; onClose: () => void }) {
  const { t } = useTranslation()
  const [mode, setMode] = useState<'existing' | 'unknown'>('existing')
  return (
    <Dialog title={t('counts.addLot')} onClose={onClose}>
      <div className="flex gap-4 mb-3">
        {(['existing', 'unknown'] as const).map((value) => (
          <label key={value} className="flex items-center gap-2 text-sm">
            <input type="radio" name="add-mode" checked={mode === value} onChange={() => setMode(value)} />
            {t(`counts.add.${value}`)}
          </label>
        ))}
      </div>
      {mode === 'existing' ? <ExistingLotForm countId={countId} onDone={onClose} /> : <UnknownLotForm countId={countId} onDone={onClose} />}
    </Dialog>
  )
}

function useAddLineErrors() {
  const { t } = useTranslation()
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [problem, setProblem] = useState<Problem>()
  const onError = (error: unknown) => {
    if (!(error instanceof ApiError)) {
      return
    }
    const fields: Record<string, string> = {}
    for (const e of error.problem.errors) {
      fields[e.field] = t(`fieldErrors.${e.code}`)
    }
    setErrors(fields)
    setProblem(error.problem.errors.length === 0 ? error.problem : undefined)
  }
  const reset = () => {
    setErrors({})
    setProblem(undefined)
  }
  return { errors, problem, onError, reset }
}

function ExistingLotForm({ countId, onDone }: { countId: number; onDone: () => void }) {
  const { t } = useTranslation()
  const add = useAddLine(countId)
  const [q, setQ] = useState('')
  const [lot, setLot] = useState<LotSummary>()
  const [counted, setCounted] = useState('')
  const { errors, problem, onError, reset } = useAddLineErrors()
  const lots = useLots({ productId: '', status: '', alertLevel: '', expiringWithinDays: '', hasStock: false, q, page: 0 })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    reset()
    if (lot) {
      add.mutate({ lotId: lot.id, countedQty: counted.trim().replace(',', '.') }, { onSuccess: onDone, onError })
    }
  }

  return lot ? (
    <form onSubmit={submit} noValidate className="flex flex-col gap-3">
      <p className="text-sm">
        {lot.lotNumber} — {lot.product.commercialName} ({formatDate(lot.expiryDate)}){' '}
        <button type="button" className="underline text-brand" onClick={() => setLot(undefined)}>
          {t('stock.adjustment.changeLot')}
        </button>
      </p>
      {(errors.lotId ?? errors.productId) && <Alert tone="danger">{errors.lotId ?? errors.productId}</Alert>}
      <TextField label={t('counts.grid.counted')} inputMode="decimal" value={counted} error={errors.countedQty} onChange={(e) => setCounted(e.target.value)} />
      {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
      <div className="flex gap-3">
        <Button type="submit" disabled={add.isPending || counted.trim() === ''}>
          {t('counts.add.submit')}
        </Button>
      </div>
    </form>
  ) : (
    <div className="flex flex-col gap-2">
      <TextField label={t('stock.adjustment.findLot')} type="search" value={q} onChange={(e) => setQ(e.target.value)} />
      {q.trim() !== '' && lots.data && (
        <ul className="flex flex-col gap-1">
          {lots.data.content.map((l) => (
            <li key={l.id} className="flex items-center gap-3 text-sm">
              <span className="font-medium">{l.lotNumber}</span>
              <span>{l.product.commercialName}</span>
              <Button variant="secondary" className="ms-auto" onClick={() => setLot(l)} aria-label={t('stock.adjustment.pickLot', { lotNumber: l.lotNumber })}>
                {t('stock.adjustment.pick')}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function UnknownLotForm({ countId, onDone }: { countId: number; onDone: () => void }) {
  const { t } = useTranslation()
  const add = useAddLine(countId)
  const [q, setQ] = useState('')
  const [product, setProduct] = useState<ProductSummary>()
  const [lotNumber, setLotNumber] = useState('')
  const [expiry, setExpiry] = useState('')
  const [unitCost, setUnitCost] = useState('')
  const [counted, setCounted] = useState('')
  const [expiryError, setExpiryError] = useState<string>()
  const { errors, problem, onError, reset } = useAddLineErrors()
  const products = useProducts({ q, categoryId: '', manufacturerId: '', ingredientId: '', speciesId: '', includeArchived: false, inStockOnly: false, page: 0 }, 10)
  const expiryDate = parseExpiry(expiry)

  const submit = (e: FormEvent) => {
    e.preventDefault()
    reset()
    setExpiryError(undefined)
    if (!product) {
      return
    }
    if (expiryDate === null) {
      setExpiryError(t(expiry.trim() === '' ? 'fieldErrors.REQUIRED' : 'fieldErrors.INVALID_FORMAT'))
      return
    }
    add.mutate(
      {
        productId: product.id,
        lotNumber: lotNumber.trim(),
        expiryDate,
        unitCost: unitCost.trim().replace(',', '.'),
        countedQty: counted.trim().replace(',', '.'),
      },
      { onSuccess: onDone, onError },
    )
  }

  if (!product) {
    return (
      <div className="flex flex-col gap-2">
        <TextField label={t('counts.findProduct')} type="search" value={q} onChange={(e) => setQ(e.target.value)} />
        {q.trim() !== '' && products.data && (
          <ul className="flex flex-col gap-1">
            {products.data.content.map((p) => (
              <li key={p.id} className="flex items-center gap-3 text-sm">
                <code>{p.sku}</code>
                <span>{p.commercialName}</span>
                <Button variant="secondary" className="ms-auto" onClick={() => setProduct(p)} aria-label={t('stock.opening.pickProduct', { name: p.commercialName })}>
                  {t('stock.adjustment.pick')}
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>
    )
  }
  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-3">
      <p className="text-sm">
        {product.commercialName}{' '}
        <button type="button" className="underline text-brand" onClick={() => setProduct(undefined)}>
          {t('stock.opening.changeProduct')}
        </button>
      </p>
      {errors.productId && <Alert tone="danger">{errors.productId}</Alert>}
      <TextField label={t('lots.lotNumber')} value={lotNumber} maxLength={60} error={errors.lotNumber} onChange={(e) => setLotNumber(e.target.value)} />
      <TextField
        label={t('stock.opening.expiry')}
        hint={expiryDate !== null ? formatDate(expiryDate) : t('stock.opening.expiryHint')}
        value={expiry}
        error={expiryError ?? errors.expiryDate}
        onChange={(e) => setExpiry(e.target.value)}
      />
      <TextField label={t('stock.opening.unitCost', { unit: product.baseUnit })} inputMode="decimal" value={unitCost} error={errors.unitCost} onChange={(e) => setUnitCost(e.target.value)} />
      <TextField label={t('counts.grid.counted')} inputMode="decimal" value={counted} error={errors.countedQty} onChange={(e) => setCounted(e.target.value)} />
      {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
      <div>
        <Button type="submit" disabled={add.isPending}>
          {t('counts.add.submit')}
        </Button>
      </div>
    </form>
  )
}

function RegularizeDialog({ count, onClose, onRefused }: {
  count: CountDetail
  onClose: () => void
  onRefused: (lineId: number) => void
}) {
  const { t } = useTranslation()
  const regularize = useRegularize(count.id)
  const accepted = count.lines.filter((l) => l.countedQty !== null && !l.regularized)
  const byProduct = new Map<number, { name: string; unit: string; variances: string[] }>()
  for (const line of accepted) {
    const entry = byProduct.get(line.product.id) ?? { name: line.product.commercialName, unit: line.product.baseUnit, variances: [] }
    entry.variances.push(line.varianceQty ?? '0')
    byProduct.set(line.product.id, entry)
  }
  const problem = regularize.error instanceof ApiError ? regularize.error.problem : undefined
  const failed = typeof problem?.context.lineId === 'number' ? count.lines.find((l) => l.id === problem.context.lineId) : undefined

  return (
    <Dialog title={t('counts.regularizeTitle', { id: count.id })} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <p className="text-sm">{t('counts.regularizeHint', { lines: accepted.length, total: count.lines.length })}</p>
        {byProduct.size > 0 && (
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-1">{t('counts.grid.product')}</th>
                <th className="text-end">{t('counts.netVariance')}</th>
              </tr>
            </thead>
            <tbody>
              {[...byProduct.values()].map((p) => (
                <tr key={p.name} className="border-b border-line">
                  <td className="py-1">{p.name}</td>
                  <td className="text-end">{`${signed(sumDecimals(p.variances) ?? '0')} ${p.unit}`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {problem && (
          <Alert tone="danger">
            {failed && (problem.code === 'STOCK_INSUFFICIENT' || problem.code === 'LOT_EXPIRY_MISMATCH')
              ? t(`counts.refused.${problem.code}`, { line: lineLabel(failed) })
              : problemMessage(problem, t)}
          </Alert>
        )}
        <div className="flex gap-3">
          <Button
            disabled={regularize.isPending}
            onClick={() =>
              regularize.mutate(undefined, {
                onSuccess: onClose,
                onError: (error) => {
                  const lineId = error instanceof ApiError ? error.problem.context.lineId : undefined
                  if (typeof lineId === 'number') {
                    onRefused(lineId)
                  }
                },
              })
            }
          >
            {t('counts.regularizeConfirm')}
          </Button>
          <Button variant="secondary" onClick={onClose}>
            {t('lots.dialog.cancel')}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}

function CancelDialog({ countId, onClose }: { countId: number; onClose: () => void }) {
  const { t } = useTranslation()
  const cancel = useCancelCount(countId)
  return (
    <Dialog title={t('counts.cancelTitle', { id: countId })} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <p className="text-sm">{t('counts.cancelHint')}</p>
        {cancel.error instanceof ApiError && <Alert tone="danger">{problemMessage(cancel.error.problem, t)}</Alert>}
        <div className="flex gap-3">
          <Button disabled={cancel.isPending} onClick={() => cancel.mutate(undefined, { onSuccess: onClose })}>
            {t('counts.cancelConfirm')}
          </Button>
          <Button variant="secondary" onClick={onClose}>
            {t('lots.dialog.cancel')}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
