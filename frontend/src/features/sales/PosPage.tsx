import { useQueryClient } from '@tanstack/react-query'
import { useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { subtractDecimal } from '../../lib/decimal'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatMoney, formatQuantity, formatUnitPrice } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { referenceLabel, useProducts, useReferenceList, type ProductSummary } from '../catalog'
import { findByBarcode } from '../catalog/productApi'
import { useCustomers } from '../customers/customerApi'
import { CashSessionBar } from './CashSessionBar'
import { PosLineDialog } from './PosLineDialog'
import {
  abandonDraft,
  addLine,
  confirmSale,
  createDraft,
  fetchSale,
  invalidateSession,
  PAYMENT_METHODS,
  removeLine,
  replaceLine,
  updateDraft,
  useCreditBalance,
  type DocType,
  type LineWrite,
  type PaymentMethod,
  type PaymentWrite,
  type SaleDetail,
  type SaleLine,
  type SaleWarning,
} from './saleApi'

const DOC_TYPES: readonly DocType[] = ['receipt', 'invoice', 'delivery_note', 'quote']

interface PaymentRow {
  method: PaymentMethod
  amount: string
  reference: string
}

const CUSTOMER_SEARCH = { type: '', active: 'true', page: 0 }
const EMPTY_ROW: PaymentRow = { method: 'cash', amount: '', reference: '' }

/**
 * `/pos` (frontend.md §4.7): one screen for the counter. The draft is created on the first line; type and customer are
 * chosen before it, since prices depend on the customer and a type never changes. The server computes prices, lots and
 * totals. Change is computed here only and never sent (ADR-118). Printing comes with Phase 10.
 */
export function PosPage() {
  const { t } = useTranslation()
  const client = useQueryClient()
  const { data: user } = useCurrentUser()
  const [docType, setDocType] = useState<DocType>('receipt')
  const [customer, setCustomer] = useState<{ id: number; name: string } | null>(null)
  const [credit, setCredit] = useState(false)
  const [speciesId, setSpeciesId] = useState('')
  const [sale, setSale] = useState<SaleDetail | null>(null)
  const [done, setDone] = useState<{ sale: SaleDetail; change: string | null } | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [badLot, setBadLot] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState<SaleLine | null>(null)
  const [openingSession, setOpeningSession] = useState(false)
  const [rows, setRows] = useState<PaymentRow[]>([EMPTY_ROW])
  const [tendered, setTendered] = useState('')
  const idempotency = useRef({ body: '', key: '' })

  const immediate = (docType === 'receipt' || docType === 'invoice') && !credit
  const canCredit = docType !== 'receipt'
  const needsCustomer = docType !== 'receipt'

  const run = async (action: () => Promise<void>) => {
    setBusy(true)
    setError(null)
    setBadLot(null)
    try {
      await action()
      return true
    } catch (failure) {
      setError(failure)
      return false
    } finally {
      setBusy(false)
    }
  }

  const add = (write: LineWrite) =>
    run(async () => {
      const draft = sale ?? (await createDraft({ docType, customerId: customer?.id ?? null, creditSale: credit }))
      setSale(draft)
      setSale(await addLine(draft.id, write))
    })

  const toggleCredit = (next: boolean) =>
    run(async () => {
      setCredit(next)
      if (sale) {
        setSale(await updateDraft(sale, next))
      }
    })

  const reset = () => {
    setSale(null)
    setDone(null)
    setCustomer(null)
    setCredit(false)
    setDocType('receipt')
    setRows([EMPTY_ROW])
    setTendered('')
    setError(null)
    setBadLot(null)
    idempotency.current = { body: '', key: '' }
  }

  const payments = (ttc: string): PaymentWrite[] => {
    if (!immediate || Number(ttc) === 0) {
      return []
    }
    return rows.map((row) => ({
      method: row.method,
      amount: rows.length === 1 ? ttc : row.amount.trim(),
      reference: row.reference.trim() === '' ? null : row.reference.trim(),
    }))
  }

  const confirm = (event: FormEvent) => {
    event.preventDefault()
    if (!sale) {
      return
    }
    const body = payments(sale.totals.ttc)
    const text = JSON.stringify([sale.id, body])
    if (idempotency.current.body !== text) {
      idempotency.current = { body: text, key: crypto.randomUUID() }
    }
    void run(async () => {
      try {
        const confirmed = await confirmSale(sale.id, body, idempotency.current.key)
        setDone({ sale: confirmed, change: changeDue(sale.totals.ttc) })
        void invalidateSession(client)
      } catch (failure) {
        if (failure instanceof ApiError) {
          if (failure.problem.code === 'CASH_SESSION_REQUIRED') {
            setOpeningSession(true)
          }
          if (failure.problem.code === 'STOCK_INSUFFICIENT' || failure.problem.code.startsWith('LOT_')) {
            const lot = failure.problem.context.lotId
            setBadLot(typeof lot === 'number' ? lot : null)
            setSale(await fetchSale(sale.id))
          }
        }
        throw failure
      }
    })
  }

  const changeDue = (ttc: string) => {
    if (!immediate || rows.length !== 1 || rows[0].method !== 'cash' || tendered.trim() === '') {
      return null
    }
    return subtractDecimal(tendered.trim(), ttc, 2)
  }

  const abandon = () =>
    run(async () => {
      if (sale) {
        await abandonDraft(sale.id)
      }
      reset()
    })

  if (done) {
    return (
      <section className="flex flex-col gap-4 max-w-md m-auto" aria-label={t('pos.done.title')}>
        <h1 className="text-2xl font-semibold">{t('pos.done.title')}</h1>
        <Alert tone="ok">{t('pos.done.number', { number: done.sale.number })}</Alert>
        <p>{t('pos.total', { amount: formatMoney(done.sale.totals.ttc) })}</p>
        {done.change !== null && Number(done.change) >= 0 && <p>{t('pos.change', { amount: formatMoney(done.change) })}</p>}
        {done.sale.customerBalance !== null && <p>{t('pos.balance', { amount: formatMoney(done.sale.customerBalance) })}</p>}
        <Button type="button" onClick={reset}>
          {t('pos.done.next')}
        </Button>
      </section>
    )
  }

  const canDiscount = hasPermission(user, 'sale.discount')
  const canOverride = hasPermission(user, 'sale.override_fefo')
  const change = sale ? changeDue(sale.totals.ttc) : null

  return (
    <div className="flex flex-col gap-3 h-full">
      <div className="flex flex-wrap items-center gap-3 justify-between">
        <h1 className="text-xl font-semibold">{t('pos.title')}</h1>
        <CashSessionBar opening={openingSession} onOpening={setOpeningSession} />
      </div>
      {error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(error.problem, t)}</Alert>
      ) : error !== null ? (
        <Alert tone="danger">{t('pos.unreachable')}</Alert>
      ) : null}
      <div className="grid grid-cols-1 lg:grid-cols-[20rem_1fr_20rem] gap-4 items-start">
        <ItemEntry
          disabled={busy || (needsCustomer && customer === null)}
          speciesId={speciesId}
          onSpecies={setSpeciesId}
          onAdd={(productId, unitId, quantity) =>
            add({ productId, unitId, quantity, targetSpeciesId: speciesId === '' ? null : Number(speciesId) })
          }
          onFailure={setError}
        />
        <Cart
          sale={sale}
          badLot={badLot}
          disabled={busy}
          onEdit={setEditing}
          onRemove={(line) => run(async () => setSale(await removeLine(line.documentId, line.id)))}
        />
        <section className="flex flex-col gap-3" aria-label={t('pos.summary')}>
          <SelectField
            label={t('pos.docType')}
            value={docType}
            disabled={sale !== null}
            onChange={(e) => {
              const next = e.target.value as DocType
              setDocType(next)
              if (next === 'receipt') {
                setCredit(false)
              }
            }}
            options={DOC_TYPES.map((type) => ({ value: type, label: t(`pos.types.${type}`) }))}
          />
          <CustomerChoice customer={customer} locked={sale !== null} onChoose={setCustomer} required={needsCustomer} />
          {canCredit && (
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={credit} disabled={busy || customer === null} onChange={(e) => void toggleCredit(e.target.checked)} />
              {t('pos.credit')}
            </label>
          )}
          {credit && customer && <CreditState customerId={customer.id} enabled={hasPermission(user, 'customer.read')} />}
          {sale && (
            <dl className="grid grid-cols-2 gap-1 text-sm">
              <dt>{t('pos.ht')}</dt>
              <dd className="text-end">{formatMoney(sale.totals.ht)}</dd>
              <dt>{t('pos.discount')}</dt>
              <dd className="text-end">{formatMoney(sale.totals.discount)}</dd>
              <dt>{t('pos.vat')}</dt>
              <dd className="text-end">{formatMoney(sale.totals.vat)}</dd>
              <dt className="font-semibold">{t('pos.ttc')}</dt>
              <dd className="text-end font-semibold" data-testid="pos-total">
                {formatMoney(sale.totals.ttc)}
              </dd>
            </dl>
          )}
          <form onSubmit={confirm} className="flex flex-col gap-3">
            {immediate && sale && Number(sale.totals.ttc) > 0 && (
              <Payments rows={rows} onRows={setRows} tendered={tendered} onTendered={setTendered} change={change} />
            )}
            <Button type="submit" disabled={busy || !sale || sale.lines.length === 0}>
              {t('pos.confirm')}
            </Button>
          </form>
          {sale && (
            <Button type="button" variant="secondary" disabled={busy} onClick={() => void abandon()}>
              {t('pos.abandon')}
            </Button>
          )}
        </section>
      </div>
      {editing && sale && (
        <PosLineDialog
          line={editing}
          canDiscount={canDiscount}
          canOverride={canOverride}
          isQuote={docType === 'quote'}
          onClose={() => setEditing(null)}
          onSave={async (write) => setSale(await replaceLine(sale.id, editing.id, write))}
        />
      )}
    </div>
  )
}

/** Scan (Enter, exact code) or search; a scanned unit is kept (BR-UNIT-005). */
function ItemEntry({
  disabled,
  speciesId,
  onSpecies,
  onAdd,
  onFailure,
}: {
  disabled: boolean
  speciesId: string
  onSpecies: (id: string) => void
  onAdd: (productId: number, unitId: number | null, quantity: string) => Promise<boolean>
  onFailure: (failure: unknown) => void
}) {
  const { t } = useTranslation()
  const [q, setQ] = useState('')
  const [quantity, setQuantity] = useState('1')
  const [unknownCode, setUnknownCode] = useState<string | null>(null)
  const scan = useRef<HTMLInputElement>(null)
  const species = useReferenceList('animal-species')
  const products = useProducts({ q, categoryId: '', manufacturerId: '', ingredientId: '', speciesId: '', includeArchived: false, inStockOnly: false, page: 0 }, 10)

  const added = () => {
    setQ('')
    setQuantity('1')
    setUnknownCode(null)
    scan.current?.focus()
  }
  const onScan = async (event: FormEvent) => {
    event.preventDefault()
    const code = q.trim()
    if (code === '') {
      return
    }
    try {
      const found = await findByBarcode(code)
      if (found) {
        if (await onAdd(found.product.id, found.unit?.id ?? null, quantity.trim())) {
          added()
        }
      } else {
        setUnknownCode(code)
      }
    } catch (failure) {
      onFailure(failure)
    }
  }
  const pick = async (product: ProductSummary) => {
    if (await onAdd(product.id, null, quantity.trim())) {
      added()
    }
  }
  return (
    <section className="flex flex-col gap-3" aria-label={t('pos.items')}>
      <form onSubmit={onScan} className="flex flex-col gap-3">
        <TextField ref={scan} autoFocus type="search" label={t('pos.scan')} value={q} onChange={(e) => { setQ(e.target.value); setUnknownCode(null) }} />
        <TextField label={t('pos.quantity')} inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
      </form>
      <SelectField
        label={t('pos.species')}
        value={speciesId}
        onChange={(e) => onSpecies(e.target.value)}
        options={[
          { value: '', label: t('pos.anySpecies') },
          ...(species.data ?? []).map((s) => ({ value: String(s.id), label: referenceLabel(s, 'species', t) })),
        ]}
      />
      {disabled && <p className="text-sm text-mute">{t('pos.chooseCustomerFirst')}</p>}
      {unknownCode !== null && <p className="text-sm text-mute">{t('pos.unknownCode', { code: unknownCode })}</p>}
      {q.trim() !== '' && products.data && (
        <ul className="flex flex-col gap-2">
          {products.data.content.map((p) => (
            <li key={p.id}>
              <Button type="button" variant="secondary" className="w-full text-start" disabled={disabled} onClick={() => void pick(p)} aria-label={t('pos.add', { name: p.commercialName })}>
                {p.commercialName} · {formatQuantity(p.sellableQtyBase)} {p.baseUnit}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function Cart({
  sale,
  badLot,
  disabled,
  onEdit,
  onRemove,
}: {
  sale: SaleDetail | null
  badLot: number | null
  disabled: boolean
  onEdit: (line: SaleLine) => void
  onRemove: (line: SaleLine & { documentId: number }) => void
}) {
  const { t } = useTranslation()
  if (!sale || sale.lines.length === 0) {
    return <p className="text-mute">{t('pos.emptyCart')}</p>
  }
  return (
    <ul className="flex flex-col gap-2" aria-label={t('pos.cart')}>
      {sale.lines.map((line) => (
        <li key={line.id} data-testid="pos-line" data-invalid={line.lotId === badLot && badLot !== null} className={`rounded-ctl border p-3 text-sm flex flex-col gap-1 ${line.lotId === badLot && badLot !== null ? 'border-danger' : 'border-line'}`}>
          <div className="flex flex-wrap items-center gap-3">
            <span className="font-medium">{line.productName}</span>
            <span>
              {formatQuantity(line.quantity)} {line.unitName} × {formatUnitPrice(line.unitPrice)}
            </span>
            {Number(line.discountPct) > 0 && <span>−{line.discountPct} %</span>}
            <span className="ms-auto font-medium">{formatMoney(line.lineTtc)}</span>
          </div>
          {line.lotNumber !== null && line.expiryDate !== null && (
            <span className="text-mute">{t('pos.lotOf', { lot: line.lotNumber, expiry: formatDate(line.expiryDate) })}</span>
          )}
          {line.warnings.map((warning, index) => (
            <span key={index} className="text-warn">
              {warningText(warning, t)}
            </span>
          ))}
          <div className="flex gap-2">
            <Button type="button" variant="secondary" disabled={disabled} onClick={() => onEdit(line)} aria-label={t('pos.editLine', { name: line.productName })}>
              {t('pos.edit')}
            </Button>
            <Button type="button" variant="secondary" disabled={disabled} onClick={() => onRemove({ ...line, documentId: sale.id })} aria-label={t('pos.removeLine', { name: line.productName })}>
              {t('pos.remove')}
            </Button>
          </div>
        </li>
      ))}
    </ul>
  )
}

function warningText(warning: SaleWarning, t: ReturnType<typeof useTranslation>['t']): string {
  switch (warning.code) {
    case 'LOT_NEAR_EXPIRY':
      return t('pos.warnings.nearExpiry', { expiry: warning.expiryDate ? formatDate(warning.expiryDate) : '' })
    case 'LOW_STOCK_AFTER_SALE':
      return t('pos.warnings.lowStock')
    case 'WITHDRAWAL_PERIOD':
      return t('pos.warnings.withdrawal', {
        species: warning.speciesName ?? '',
        meat: warning.meatDays ?? '—',
        milk: warning.milkDays ?? '—',
        eggs: warning.eggsDays ?? '—',
      })
  }
}

function CustomerChoice({
  customer,
  locked,
  required,
  onChoose,
}: {
  customer: { id: number; name: string } | null
  locked: boolean
  required: boolean
  onChoose: (customer: { id: number; name: string } | null) => void
}) {
  const { t } = useTranslation()
  const [q, setQ] = useState('')
  const found = useCustomers({ ...CUSTOMER_SEARCH, q }, 5, q.trim() !== '' && !customer)
  if (customer) {
    return (
      <div className="flex items-center gap-2 text-sm">
        <span data-testid="pos-customer">{customer.name}</span>
        {!locked && (
          <Button type="button" variant="secondary" className="ms-auto" onClick={() => onChoose(null)}>
            {t('pos.changeCustomer')}
          </Button>
        )}
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-2">
      <TextField
        type="search"
        label={required ? t('pos.customerRequired') : t('pos.customerOptional')}
        disabled={locked}
        value={q}
        onChange={(e) => setQ(e.target.value)}
      />
      {q.trim() !== '' && found.data && (
        <ul className="flex flex-col gap-2">
          {found.data.content.map((c) => (
            <li key={c.id}>
              <Button type="button" variant="secondary" className="w-full text-start" onClick={() => { onChoose({ id: c.id, name: c.name }); setQ('') }} aria-label={t('pos.chooseCustomer', { name: c.name })}>
                {c.name}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function CreditState({ customerId, enabled }: { customerId: number; enabled: boolean }) {
  const { t } = useTranslation()
  const balance = useCreditBalance(customerId, enabled)
  if (!balance.data) {
    return null
  }
  return (
    <p className="text-sm" data-testid="pos-credit-state">
      {t('pos.balance', { amount: formatMoney(balance.data.balance) })} ·{' '}
      {balance.data.availableCredit === null ? t('customers.credit.none') : t('pos.available', { amount: formatMoney(balance.data.availableCredit) })}
    </p>
  )
}

/** One row pays the whole total; several rows give their amounts, and the server checks the sum (ADR-118). */
function Payments({
  rows,
  onRows,
  tendered,
  onTendered,
  change,
}: {
  rows: PaymentRow[]
  onRows: (rows: PaymentRow[]) => void
  tendered: string
  onTendered: (value: string) => void
  change: string | null
}) {
  const { t } = useTranslation()
  const update = (index: number, patch: Partial<PaymentRow>) => onRows(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)))
  return (
    <fieldset className="flex flex-col gap-3 border-t border-line pt-3">
      <legend className="text-sm font-medium">{t('pos.payment')}</legend>
      {rows.map((row, index) => (
        <div key={index} className="flex flex-col gap-2">
          <SelectField
            label={t('pos.method')}
            value={row.method}
            onChange={(e) => update(index, { method: e.target.value as PaymentMethod })}
            options={PAYMENT_METHODS.map((method) => ({ value: method, label: t(`pos.methods.${method}`) }))}
          />
          {rows.length > 1 && <TextField label={t('pos.amount')} inputMode="decimal" required value={row.amount} onChange={(e) => update(index, { amount: e.target.value })} />}
          {row.method !== 'cash' && <TextField label={t('pos.reference')} required maxLength={80} value={row.reference} onChange={(e) => update(index, { reference: e.target.value })} />}
        </div>
      ))}
      <Button type="button" variant="secondary" onClick={() => onRows([...rows, EMPTY_ROW])}>
        {t('pos.split')}
      </Button>
      {rows.length === 1 && rows[0].method === 'cash' && (
        <TextField label={t('pos.tendered')} hint={t('pos.tenderedHint')} inputMode="decimal" value={tendered} onChange={(e) => onTendered(e.target.value)} />
      )}
      {change !== null && Number(change) >= 0 && <p data-testid="pos-change">{t('pos.change', { amount: formatMoney(change) })}</p>}
    </fieldset>
  )
}
