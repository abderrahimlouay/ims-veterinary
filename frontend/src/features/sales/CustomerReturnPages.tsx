import { useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { apiFetch, ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Pager, type PageMetadata } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatDateTime, formatMoney, formatQuantity } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { CashSessionBar } from './CashSessionBar'
import {
  DISCARD_REASONS,
  previewReturn,
  useCreateCustomerReturn,
  useCustomerReturn,
  useCustomerReturns,
  type DiscardReason,
  type Disposition,
  type PreviewLine,
  type ReturnLineWrite,
  type ReturnPreview,
} from './returnApi'
import { PAYMENT_METHODS, type PaymentMethod, type SaleSummary } from './saleApi'

/** `/returns/customer` (frontend.md §4.8): returns newest first, by document; each opens its detail. */
export function CustomerReturnsPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const [params, setParams] = useSearchParams()
  const documentId = params.get('documentId') ?? ''
  const page = Number(params.get('page') ?? '0')
  const returns = useCustomerReturns({ customerId: params.get('customerId') ?? '', documentId, page })
  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{t('returns.title')}</h1>
        {hasPermission(current, 'return.customer.create') && (
          <Link to="/returns/customer/new" className="ms-auto h-10 px-4 inline-flex items-center rounded-ctl bg-brand text-brand-ink text-sm font-medium">
            {t('returns.new')}
          </Link>
        )}
      </div>
      {returns.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : returns.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(returns.error.problem, t)}</Alert>
      ) : !returns.data || returns.data.content.length === 0 ? (
        <p className="text-mute">{t('returns.empty')}</p>
      ) : (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('sales.number')}</th>
                <th className="text-start">{t('returns.document')}</th>
                <th className="text-start">{t('sales.customer')}</th>
                <th className="text-start">{t('returns.creditNote')}</th>
                <th className="text-end">{t('sales.total')}</th>
                <th className="text-start">{t('returns.settlement')}</th>
                <th className="text-start">{t('sales.date')}</th>
              </tr>
            </thead>
            <tbody>
              {returns.data.content.map((r) => (
                <tr key={r.id} className="border-b border-line">
                  <td className="py-2">
                    <Link to={`/returns/customer/${r.id}`} className="text-brand underline underline-offset-4">
                      {r.number}
                    </Link>
                  </td>
                  <td>
                    <Link to={`/sales/${r.document.id}`} className="underline">
                      {r.document.number}
                    </Link>
                  </td>
                  <td>{r.customer?.name ?? t('sales.anonymous')}</td>
                  <td>
                    <Link to={`/sales/${r.creditNote.id}`} className="underline">
                      {r.creditNote.number}
                    </Link>
                  </td>
                  <td className="text-end">{formatMoney(r.creditNote.totalTtc)}</td>
                  <td>{r.refunded ? t('returns.refunded') : t('returns.credited')}</td>
                  <td>{formatDateTime(r.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={returns.data.page} onPage={(next) => setParams({ ...(documentId === '' ? {} : { documentId }), page: String(next) }, { replace: true })} />
        </>
      )}
    </section>
  )
}

interface LineState {
  quantity: string
  sealed: boolean
  disposition: Disposition
  discardReason: DiscardReason | ''
  discardNote: string
}

interface TenderState {
  method: PaymentMethod
  amount: string
  reference: string
}

const emptyLine = (line: PreviewLine): LineState => ({
  quantity: '',
  sealed: true,
  disposition: line.restockAllowed ? 'restock' : 'discard',
  discardReason: line.restockRefusal === 'lot_expired' ? 'expired' : '',
  discardNote: '',
})

/** Only the lines with a quantity are returned; quantities in base units, as the preview shows them. */
function toWrite(lines: PreviewLine[], state: Record<number, LineState>): { write: ReturnLineWrite[]; rows: number[] } {
  const write: ReturnLineWrite[] = []
  const rows: number[] = []
  lines.forEach((line, row) => {
    const s = state[line.saleLineId]
    if (!s || s.quantity.trim() === '') {
      return
    }
    rows.push(row)
    write.push({
      saleLineId: line.saleLineId,
      quantity: s.quantity.trim().replace(',', '.'),
      unit: 'base',
      sealed: s.sealed,
      disposition: s.disposition,
      ...(s.disposition === 'discard'
        ? { discardReason: s.discardReason === '' ? undefined : s.discardReason, discardNote: s.discardNote.trim() === '' ? undefined : s.discardNote.trim() }
        : {}),
    })
  })
  return { write, rows }
}

/**
 * `/returns/customer/new?documentId=` (W3, frontend.md §4.8): find the invoice or receipt, enter per line the quantity
 * (at most what is left), whether the goods are sealed and the disposition — restock is offered only where the server
 * allows it, with its reason otherwise (ADR-129); a discard needs its reason. The review asks the server for the credit
 * note; an immediate sale is then refunded in full (cash needs the user's own open session), a credit sale credited to
 * the account (ADR-128). One idempotency key per return being entered.
 */
export function NewCustomerReturnPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const documentId = params.get('documentId')
  const [number, setNumber] = useState('')
  const [findError, setFindError] = useState<string>()
  const [preview, setPreview] = useState<ReturnPreview>()
  const [review, setReview] = useState<ReturnPreview>()
  const [lines, setLines] = useState<Record<number, LineState>>({})
  const [reason, setReason] = useState('')
  const [tenders, setTenders] = useState<TenderState[]>([])
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [problem, setProblem] = useState<Problem>()
  const [opening, setOpening] = useState(false)
  const [key] = useState(() => crypto.randomUUID())
  const create = useCreateCustomerReturn()

  useEffect(() => {
    if (documentId === null) {
      return
    }
    previewReturn(Number(documentId), [])
      .then((loaded) => {
        setPreview(loaded)
        setLines(Object.fromEntries(loaded.lines.map((l) => [l.saleLineId, emptyLine(l)])))
        setProblem(undefined)
      })
      .catch((error: unknown) => {
        setPreview(undefined)
        setProblem(error instanceof ApiError ? error.problem : undefined)
      })
  }, [documentId])

  const find = async (event: FormEvent) => {
    event.preventDefault()
    setFindError(undefined)
    const found = await apiFetch<{ content: SaleSummary[]; page: PageMetadata }>(`/api/sales?number=${encodeURIComponent(number.trim())}&size=5`)
    const document = found.content.find((d) => d.docType === 'invoice' || d.docType === 'receipt' || d.docType === 'delivery_note')
    if (!document) {
      setFindError(t('returns.notFound'))
      return
    }
    setReview(undefined)
    setParams({ documentId: String(document.id) }, { replace: true })
  }

  const update = (saleLineId: number, change: Partial<LineState>) => {
    setReview(undefined)
    setLines((ls) => ({ ...ls, [saleLineId]: { ...ls[saleLineId], ...change } }))
  }

  /** Server field errors name `lines[i]` of what was sent; `rows` maps them back to the table. */
  const placeErrors = (error: unknown, rows: number[]) => {
    if (!(error instanceof ApiError)) {
      return
    }
    const placed: Record<string, string> = {}
    for (const e of error.problem.errors) {
      const match = /^lines\[(\d+)\]\.(\w+)$/.exec(e.field)
      placed[match ? `row${rows[Number(match[1])]}.${match[2]}` : e.field] = t(`fieldErrors.${e.code}`)
    }
    setErrors(placed)
    if (Object.keys(placed).length === 0) {
      setProblem(error.problem)
    }
  }

  const doReview = async () => {
    if (!preview) {
      return
    }
    setErrors({})
    setProblem(undefined)
    const { write, rows } = toWrite(preview.lines, lines)
    if (write.length === 0) {
      setErrors({ lines: t('returns.noLine') })
      return
    }
    try {
      const reviewed = await previewReturn(preview.document.id, write)
      setReview(reviewed)
      setTenders(reviewed.refundRequired ? [{ method: 'cash', amount: reviewed.creditNote.totalTtc, reference: '' }] : [])
    } catch (error) {
      placeErrors(error, rows)
    }
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!preview || !review) {
      return
    }
    if (reason.trim() === '') {
      setErrors({ reason: t('fieldErrors.REQUIRED') })
      return
    }
    const { write, rows } = toWrite(preview.lines, lines)
    setErrors({})
    setProblem(undefined)
    create.mutate(
      {
        key,
        body: {
          documentId: preview.document.id,
          reason: reason.trim(),
          lines: write,
          refund: tenders.map((tender) => ({ method: tender.method, amount: tender.amount.trim().replace(',', '.'), reference: tender.reference.trim() === '' ? null : tender.reference.trim() })),
        },
      },
      { onSuccess: (created) => void navigate(`/returns/customer/${created.id}`), onError: (error) => placeErrors(error, rows) },
    )
  }

  return (
    <section className="flex flex-col gap-4 max-w-5xl">
      <h1 className="text-2xl font-semibold">{t('returns.new')}</h1>
      <form onSubmit={(e) => void find(e)} className="flex items-end gap-3 max-w-xl">
        <TextField label={t('returns.findDocument')} value={number} error={findError} onChange={(e) => setNumber(e.target.value)} />
        <Button type="submit" variant="secondary" disabled={number.trim() === ''}>
          {t('returns.find')}
        </Button>
      </form>
      {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
      {preview && (
        <form onSubmit={submit} noValidate className="flex flex-col gap-4">
          <p className="text-sm">
            {t('returns.of', {
              number: preview.document.number,
              customer: preview.document.customer?.name ?? t('sales.anonymous'),
              date: formatDateTime(preview.document.issuedAt),
            })}
            {' · '}
            {preview.refundRequired ? t('returns.refundRequired') : t('returns.creditedToAccount')}
          </p>
          {errors.lines && <Alert tone="danger">{errors.lines}</Alert>}
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('sales.product')}</th>
                <th className="text-start">{t('sales.lot')}</th>
                <th className="text-end">{t('returns.returnable')}</th>
                <th className="text-start">{t('returns.quantity')}</th>
                <th className="text-start">{t('returns.sealed')}</th>
                <th className="text-start">{t('returns.disposition')}</th>
              </tr>
            </thead>
            <tbody>
              {preview.lines.map((line, row) => {
                const s = lines[line.saleLineId]
                const restockable = line.restockAllowed && s.sealed
                return (
                  <tr key={line.saleLineId} className="border-b border-line align-top">
                    <td className="py-2">{line.product.commercialName}</td>
                    <td>
                      {line.lot.lotNumber} · {formatDate(line.lot.expiryDate)}
                      {line.lot.status !== 'active' && <span className="block text-xs text-warn">{t(`returns.lotStatus.${line.lot.status}`)}</span>}
                    </td>
                    <td className="text-end">{`${formatQuantity(line.returnableBase)} ${line.product.baseUnit}`}</td>
                    <td className="w-32">
                      <TextField
                        label={t('returns.quantityOf', { name: line.product.commercialName })}
                        inputMode="decimal"
                        value={s.quantity}
                        error={errors[`row${row}.quantity`] ?? errors[`row${row}.saleLineId`]}
                        disabled={Number(line.returnableBase) === 0}
                        onChange={(e) => update(line.saleLineId, { quantity: e.target.value })}
                      />
                    </td>
                    <td>
                      <label className="flex items-center gap-2 pt-7">
                        <input
                          type="checkbox"
                          checked={s.sealed}
                          aria-label={t('returns.sealedOf', { name: line.product.commercialName })}
                          onChange={(e) => update(line.saleLineId, { sealed: e.target.checked, ...(e.target.checked ? {} : { disposition: 'discard' }) })}
                        />
                        {t('returns.sealed')}
                      </label>
                    </td>
                    <td className="flex flex-col gap-2 py-2 w-72">
                      <SelectField
                        label={t('returns.dispositionOf', { name: line.product.commercialName })}
                        value={s.disposition}
                        error={errors[`row${row}.disposition`]}
                        onChange={(e) => update(line.saleLineId, { disposition: e.target.value as Disposition })}
                        options={[
                          ...(restockable ? [{ value: 'restock', label: t('returns.dispositions.restock') }] : []),
                          { value: 'discard', label: t('returns.dispositions.discard') },
                        ]}
                      />
                      {!line.restockAllowed && line.restockRefusal && <span className="text-xs text-mute">{t(`returns.refusals.${line.restockRefusal}`)}</span>}
                      {line.restockAllowed && line.lot.status !== 'active' && s.disposition === 'restock' && <span className="text-xs text-mute">{t('returns.staysUnsellable')}</span>}
                      {s.disposition === 'discard' && (
                        <>
                          <SelectField
                            label={t('returns.discardReasonOf', { name: line.product.commercialName })}
                            value={s.discardReason}
                            error={errors[`row${row}.discardReason`]}
                            onChange={(e) => update(line.saleLineId, { discardReason: e.target.value as DiscardReason | '' })}
                            options={[{ value: '', label: '—' }, ...DISCARD_REASONS.map((r) => ({ value: r, label: t(`returns.reasons.${r}`) }))]}
                          />
                          {s.discardReason === 'other' && (
                            <TextField label={t('returns.discardNote')} value={s.discardNote} error={errors[`row${row}.discardNote`]} onChange={(e) => update(line.saleLineId, { discardNote: e.target.value })} />
                          )}
                        </>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          <div className="w-96">
            <TextField label={t('returns.reason')} value={reason} error={errors.reason} onChange={(e) => setReason(e.target.value)} />
          </div>
          <div>
            <Button type="button" variant="secondary" onClick={() => void doReview()}>
              {t('returns.review')}
            </Button>
          </div>
          {review && (
            <section className="flex flex-col gap-3 rounded-ctl border border-line p-4" aria-label={t('returns.creditNote')}>
              <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 text-sm max-w-xs">
                <dt>{t('pos.ht')}</dt>
                <dd className="text-end">{formatMoney(review.creditNote.totalHt)}</dd>
                <dt>{t('pos.vat')}</dt>
                <dd className="text-end">{formatMoney(review.creditNote.totalVat)}</dd>
                <dt className="font-semibold">{t('returns.creditTotal')}</dt>
                <dd className="text-end font-semibold" data-testid="credit-total">
                  {formatMoney(review.creditNote.totalTtc)}
                </dd>
              </dl>
              {review.refundRequired ? (
                <>
                  <h2 className="text-lg font-semibold">{t('returns.refund')}</h2>
                  {tenders.some((tender) => tender.method === 'cash') && <CashSessionBar opening={opening} onOpening={setOpening} />}
                  {tenders.map((tender, i) => (
                    <div key={i} className="flex flex-wrap items-end gap-3">
                      <div className="w-48">
                        <SelectField
                          label={t('pos.method')}
                          value={tender.method}
                          onChange={(e) => setTenders((ts) => ts.map((x, j) => (j === i ? { ...x, method: e.target.value as PaymentMethod } : x)))}
                          options={PAYMENT_METHODS.map((m) => ({ value: m, label: t(`pos.methods.${m}`) }))}
                        />
                      </div>
                      <div className="w-36">
                        <TextField
                          label={t('pos.amount')}
                          inputMode="decimal"
                          value={tender.amount}
                          error={errors[`refund[${i}].amount`]}
                          onChange={(e) => setTenders((ts) => ts.map((x, j) => (j === i ? { ...x, amount: e.target.value } : x)))}
                        />
                      </div>
                      {tender.method !== 'cash' && (
                        <div className="w-48">
                          <TextField
                            label={t('pos.reference')}
                            value={tender.reference}
                            error={errors[`refund[${i}].reference`]}
                            onChange={(e) => setTenders((ts) => ts.map((x, j) => (j === i ? { ...x, reference: e.target.value } : x)))}
                          />
                        </div>
                      )}
                      {tenders.length > 1 && (
                        <Button type="button" variant="secondary" onClick={() => setTenders((ts) => ts.filter((_, j) => j !== i))}>
                          {t('pos.remove')}
                        </Button>
                      )}
                    </div>
                  ))}
                  <div>
                    <Button type="button" variant="secondary" onClick={() => setTenders((ts) => [...ts, { method: 'cheque', amount: '', reference: '' }])}>
                      {t('pos.split')}
                    </Button>
                  </div>
                </>
              ) : (
                <p className="text-sm">{t('returns.creditedToAccount')}</p>
              )}
              <div>
                <Button type="submit" disabled={create.isPending}>
                  {t('returns.confirm')}
                </Button>
              </div>
            </section>
          )}
        </form>
      )}
    </section>
  )
}

/** `/returns/customer/:id`: the returned lines, their dispositions, the credit note and the refunds; never edited. */
export function CustomerReturnPage() {
  const { t } = useTranslation()
  const customerReturn = useCustomerReturn(Number(useParams().id))
  if (customerReturn.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (customerReturn.error instanceof ApiError || !customerReturn.data) {
    return <Alert tone="danger">{customerReturn.error instanceof ApiError ? problemMessage(customerReturn.error.problem, t) : t('errors.UNKNOWN')}</Alert>
  }
  const r = customerReturn.data
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{r.number}</h1>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm max-w-xl">
        <dt>{t('returns.document')}</dt>
        <dd>
          <Link to={`/sales/${r.document.id}`} className="text-brand underline underline-offset-4">
            {r.document.number}
          </Link>
        </dd>
        <dt>{t('sales.customer')}</dt>
        <dd>{r.customer?.name ?? t('sales.anonymous')}</dd>
        <dt>{t('sales.date')}</dt>
        <dd>{formatDateTime(r.createdAt)}</dd>
        <dt>{t('returns.reason')}</dt>
        <dd>{r.reason}</dd>
        <dt>{t('returns.creditNote')}</dt>
        <dd data-testid="credit-note">
          <Link to={`/sales/${r.creditNote.id}`} className="text-brand underline underline-offset-4">
            {r.creditNote.number}
          </Link>
          {` · ${formatMoney(r.creditNote.totalTtc)}`}
        </dd>
      </dl>
      <table className="w-full text-sm border-collapse">
        <thead>
          <tr className="border-b border-line">
            <th className="text-start py-2">{t('sales.product')}</th>
            <th className="text-start">{t('sales.lot')}</th>
            <th className="text-end">{t('returns.quantity')}</th>
            <th className="text-start">{t('returns.sealed')}</th>
            <th className="text-start">{t('returns.disposition')}</th>
          </tr>
        </thead>
        <tbody>
          {r.lines.map((l) => (
            <tr key={l.id} className="border-b border-line">
              <td className="py-2">{l.product.commercialName}</td>
              <td>
                <Link to={`/lots/${l.lot.id}`} className="underline">
                  {l.lot.lotNumber}
                </Link>
              </td>
              <td className="text-end">{`${formatQuantity(l.quantityBase)} ${l.product.baseUnit}`}</td>
              <td>{l.sealed ? t('returns.yes') : t('returns.no')}</td>
              <td>
                {t(`returns.dispositions.${l.disposition}`)}
                {l.discardReason && ` · ${t(`returns.reasons.${l.discardReason}`)}`}
                {l.discardNote && ` · ${l.discardNote}`}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <section className="flex flex-col gap-1 text-sm">
        <h2 className="text-lg font-semibold">{t('returns.settlement')}</h2>
        {r.refunds.length === 0 ? (
          <p>{t('returns.creditedToAccount')}</p>
        ) : (
          <ul>
            {r.refunds.map((f) => (
              <li key={f.id}>
                {t(`pos.methods.${f.method}`)} · {formatMoney(f.amount)}
                {f.reference && ` · ${f.reference}`}
              </li>
            ))}
          </ul>
        )}
      </section>
    </section>
  )
}
