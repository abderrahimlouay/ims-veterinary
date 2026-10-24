import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { businessToday, parseExpiry } from '../../lib/expiry'
import { formatQuantity } from '../../lib/format'
import { useUnits, type ProductSummary } from '../catalog'
import { useOrder, type PurchaseOrderDetail } from './orderApi'
import { ProductPicker } from './ProductPicker'
import { useConfirmReceipt } from './receiptApi'
import { useSuppliers } from './supplierApi'

interface LineState {
  key: number
  poLineId: number | null
  productId: number
  name: string
  baseUnit: string
  outstanding: string | null
  unitId: string
  quantity: string
  lotNumber: string
  expiry: string
  unitCost: string
  conforming: boolean
}

interface PendingLot {
  lotId: number
  lotNumber: string
  quantityInStock: string
}

type LineField = 'productId' | 'poLineId' | 'unitId' | 'quantity' | 'lotNumber' | 'expiryDate' | 'unitCost'

/**
 * `/goods-receipts/new` (frontend.md §4.5, W1b): from a sent or partially received order (`?orderId=`, lines
 * prefilled with what is outstanding, in base units) or a free receipt (an active supplier, products searched).
 * Per line: lot number and expiry (a date or `MM/AAAA`) required, unit, quantity, cost per base unit, conformity;
 * an order line can be received in several lots. An expired conforming line is flagged before sending. When a
 * non-conforming line would block a lot that already has stock, the server names the lots and quantities and the
 * user confirms explicitly (ADR-095). One `Idempotency-Key` per receipt being entered; the button is disabled while
 * the request is in flight (ADR-104).
 */
export function NewReceiptPage() {
  const { t } = useTranslation()
  const orderId = useSearchParams()[0].get('orderId')
  const order = useOrder(Number(orderId), orderId !== null)
  if (orderId === null) {
    return (
      <section className="flex flex-col gap-4">
        <h1 className="text-2xl font-semibold">{t('purchasing.receipts.newFree')}</h1>
        <ReceiptForm />
      </section>
    )
  }
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
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{t('purchasing.receipts.newFor', { number: order.data.number })}</h1>
      {order.data.status === 'sent' || order.data.status === 'partially_received' ? (
        <ReceiptForm order={order.data} />
      ) : (
        <Alert tone="danger">{t('purchasing.receipts.notReceivable')}</Alert>
      )}
    </section>
  )
}

function ReceiptForm({ order }: { order?: PurchaseOrderDetail }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const confirm = useConfirmReceipt()
  const suppliers = useSuppliers('', 'true', 0, 100)
  const [key] = useState(() => crypto.randomUUID())
  const [supplierId, setSupplierId] = useState('')
  const [docRef, setDocRef] = useState('')
  const [lines, setLines] = useState<LineState[]>(
    (order?.lines ?? [])
      .filter((l) => Number(l.outstandingBase) > 0)
      .map((l, i) => ({
        key: i,
        poLineId: l.id,
        productId: l.product.id,
        name: l.product.commercialName,
        baseUnit: l.product.baseUnit,
        outstanding: l.outstandingBase,
        unitId: '',
        quantity: l.outstandingBase,
        lotNumber: '',
        expiry: '',
        unitCost: '',
        conforming: true,
      })),
  )
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [problem, setProblem] = useState<Problem>()
  const [toBlock, setToBlock] = useState<PendingLot[]>()
  const nextKey = () => Math.max(-1, ...lines.map((l) => l.key)) + 1
  const update = (k: number, patch: Partial<LineState>) => setLines((ls) => ls.map((l) => (l.key === k ? { ...l, ...patch } : l)))

  const addProduct = (product: ProductSummary) =>
    setLines((ls) => [...ls, { key: nextKey(), poLineId: null, productId: product.id, name: product.commercialName, baseUnit: product.baseUnit, outstanding: null, unitId: '', quantity: '', lotNumber: '', expiry: '', unitCost: '', conforming: true }])

  const send = (confirmLotBlocking: boolean) => {
    const local: Record<string, string> = {}
    lines.forEach((l, i) => {
      if (l.lotNumber.trim() === '') {
        local[`lines[${i}].lotNumber`] = t('fieldErrors.REQUIRED')
      }
      if (parseExpiry(l.expiry) === null) {
        local[`lines[${i}].expiryDate`] = t(l.expiry.trim() === '' ? 'fieldErrors.REQUIRED' : 'fieldErrors.INVALID_FORMAT')
      }
    })
    if (order === undefined && supplierId === '') {
      local.supplierId = t('fieldErrors.REQUIRED')
    }
    setErrors(local)
    setProblem(undefined)
    if (Object.keys(local).length > 0) {
      return
    }
    const decimal = (v: string) => v.trim().replace(',', '.')
    confirm.mutate(
      {
        key,
        receipt: {
          supplierId: order ? order.supplier.id : Number(supplierId),
          purchaseOrderId: order ? order.id : null,
          supplierDocRef: docRef.trim() === '' ? null : docRef.trim(),
          notes: null,
          confirmLotBlocking,
          lines: lines.map((l) => ({
            poLineId: l.poLineId,
            productId: l.productId,
            unitId: l.unitId === '' ? null : Number(l.unitId),
            quantity: decimal(l.quantity),
            lotNumber: l.lotNumber.trim(),
            expiryDate: parseExpiry(l.expiry) as string,
            unitCost: decimal(l.unitCost),
            conforming: l.conforming,
          })),
        },
      },
      {
        onSuccess: (receipt) => navigate(`/goods-receipts/${receipt.id}`),
        onError: (error) => {
          if (!(error instanceof ApiError)) {
            return
          }
          if (error.problem.code === 'LOT_BLOCK_CONFIRMATION_REQUIRED') {
            setToBlock((error.problem.context.lots as PendingLot[] | undefined) ?? [])
            return
          }
          const placed = Object.fromEntries(error.problem.errors.map((e) => [e.field, t(`fieldErrors.${e.code}`)]))
          setErrors(placed)
          if (Object.keys(placed).length === 0) {
            setProblem(error.problem)
          }
        },
      },
    )
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setToBlock(undefined)
    send(false)
  }

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-4">
      {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
      {errors.lines && <Alert tone="danger">{errors.lines}</Alert>}
      {toBlock && (
        <Alert tone="danger">
          {t('purchasing.receipts.blockWarning')}
          <ul className="list-disc ps-6">
            {toBlock.map((l) => (
              <li key={l.lotId}>{t('purchasing.receipts.blockLot', { lotNumber: l.lotNumber, quantity: formatQuantity(l.quantityInStock) })}</li>
            ))}
          </ul>
          <Button type="button" className="mt-2" disabled={confirm.isPending} onClick={() => send(true)}>
            {t('purchasing.receipts.blockConfirm')}
          </Button>
        </Alert>
      )}
      <div className="flex flex-wrap gap-4">
        {order ? (
          <p className="text-sm">{t('purchasing.receipts.fromSupplier', { name: order.supplier.name })}</p>
        ) : (
          <div className="w-72">
            <SelectField
              label={t('purchasing.orders.supplier')}
              value={supplierId}
              error={errors.supplierId}
              onChange={(e) => setSupplierId(e.target.value)}
              options={[{ value: '', label: '—' }, ...(suppliers.data?.content ?? []).map((s) => ({ value: String(s.id), label: s.name }))]}
            />
          </div>
        )}
        <div className="w-56">
          <TextField label={t('purchasing.receipts.docRef')} value={docRef} error={errors.supplierDocRef} onChange={(e) => setDocRef(e.target.value)} />
        </div>
      </div>
      {lines.length === 0 ? (
        <p className="text-mute">{t('purchasing.receipts.noLines')}</p>
      ) : (
        lines.map((line, i) => (
          <ReceiptLineEditor
            key={line.key}
            line={line}
            error={(field) => errors[`lines[${i}].${field}`]}
            onChange={(patch) => update(line.key, patch)}
            onSplit={line.poLineId === null ? undefined : () => setLines((ls) => [...ls, { ...line, key: nextKey(), quantity: '', lotNumber: '', expiry: '' }])}
            onRemove={() => setLines((ls) => ls.filter((l) => l.key !== line.key))}
          />
        ))
      )}
      {order === undefined && (
        <div className="max-w-xl">
          <ProductPicker onPick={addProduct} label={t('purchasing.orders.addProduct')} />
        </div>
      )}
      <div>
        <Button type="submit" disabled={confirm.isPending || lines.length === 0}>
          {t('purchasing.receipts.confirm')}
        </Button>
      </div>
    </form>
  )
}

function ReceiptLineEditor({ line, error, onChange, onSplit, onRemove }: {
  line: LineState
  error: (field: LineField) => string | undefined
  onChange: (patch: Partial<LineState>) => void
  onSplit?: () => void
  onRemove: () => void
}) {
  const { t } = useTranslation()
  const units = useUnits(line.productId)
  const expiry = parseExpiry(line.expiry)
  const expired = line.conforming && expiry !== null && expiry < businessToday()
  return (
    <fieldset className="flex flex-wrap items-end gap-3 rounded-ctl border border-line p-3">
      <legend className="px-1 text-sm font-medium">
        {line.name}
        {line.outstanding !== null && ` — ${t('purchasing.receipts.outstanding', { quantity: `${formatQuantity(line.outstanding)} ${line.baseUnit}` })}`}
      </legend>
      {(error('productId') ?? error('poLineId')) && <p className="w-full text-sm text-danger">{error('productId') ?? error('poLineId')}</p>}
      <div className="w-40">
        <TextField label={t('purchasing.receipts.lotNumber')} value={line.lotNumber} error={error('lotNumber')} onChange={(e) => onChange({ lotNumber: e.target.value })} />
      </div>
      <div className="w-40">
        <TextField
          label={t('purchasing.receipts.expiry')}
          placeholder={t('purchasing.receipts.expiryHint')}
          value={line.expiry}
          error={error('expiryDate') ?? (expired ? t('purchasing.receipts.expired') : undefined)}
          onChange={(e) => onChange({ expiry: e.target.value })}
        />
      </div>
      <div className="w-36">
        <SelectField
          label={t('purchasing.orders.unit')}
          value={line.unitId}
          error={error('unitId')}
          onChange={(e) => onChange({ unitId: e.target.value })}
          options={[{ value: '', label: line.baseUnit }, ...(units.data ?? []).map((u) => ({ value: String(u.id), label: u.name }))]}
        />
      </div>
      <div className="w-28">
        <TextField label={t('purchasing.orders.quantity')} inputMode="decimal" value={line.quantity} error={error('quantity')} onChange={(e) => onChange({ quantity: e.target.value })} />
      </div>
      <div className="w-36">
        <TextField label={t('purchasing.receipts.unitCost')} inputMode="decimal" value={line.unitCost} error={error('unitCost')} onChange={(e) => onChange({ unitCost: e.target.value })} />
      </div>
      <label className="flex items-center gap-2 h-10 text-sm">
        <input type="checkbox" checked={line.conforming} onChange={(e) => onChange({ conforming: e.target.checked })} />
        {t('purchasing.receipts.conforming')}
      </label>
      {onSplit && (
        <Button type="button" variant="secondary" onClick={onSplit}>
          {t('purchasing.receipts.addLot')}
        </Button>
      )}
      <Button type="button" variant="secondary" onClick={onRemove} aria-label={t('purchasing.orders.removeLine', { name: line.name })}>
        {t('purchasing.orders.remove')}
      </Button>
    </fieldset>
  )
}
