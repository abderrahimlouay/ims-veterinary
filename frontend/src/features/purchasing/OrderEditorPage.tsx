import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { useUnits, type ProductSummary } from '../catalog'
import { fetchPrefill, useOrder, useSaveOrder, type PurchaseOrderDetail } from './orderApi'
import { ProductPicker } from './ProductPicker'
import { useSuppliers } from './supplierApi'

interface LineState {
  key: number
  productId: number
  name: string
  baseUnit: string
  unitId: string
  quantity: string
  unitPrice: string
  discountPct: string
  vatRate: string
}

type LineField = 'productId' | 'unitId' | 'quantity' | 'unitPrice' | 'discountPct' | 'vatRate'

/** `/purchase-orders/new` (frontend.md §4.5): a new draft order. */
export function NewOrderPage() {
  const { t } = useTranslation()
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{t('purchasing.orders.new')}</h1>
      <OrderEditor />
    </section>
  )
}

/** `/purchase-orders/:id/edit`: a draft replaced as a whole with its version (BR-PURCH-003, ADR-027). */
export function EditOrderPage() {
  const { t } = useTranslation()
  const order = useOrder(Number(useParams().id))
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
      <h1 className="text-2xl font-semibold">{t('purchasing.orders.edit', { number: order.data.number })}</h1>
      {order.data.status === 'draft' ? <OrderEditor order={order.data} /> : <Alert tone="danger">{t('errors.DOCUMENT_IMMUTABLE')}</Alert>}
    </section>
  )
}

/**
 * Supplier (active ones only) and lines: product search, unit, quantity, price per ordered unit prefilled from the
 * supplier's latest price or the product's purchase price, discount and VAT prefilled (ADR-094/099/105); a
 * product once per order. No amount is sent: the server computes them (BR-PURCH-002).
 */
function OrderEditor({ order }: { order?: PurchaseOrderDetail }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const save = useSaveOrder()
  const suppliers = useSuppliers('', 'true', 0, 100)
  const [supplierId, setSupplierId] = useState(order ? String(order.supplier.id) : '')
  const [expectedDate, setExpectedDate] = useState(order?.expectedDate ?? '')
  const [lines, setLines] = useState<LineState[]>(
    (order?.lines ?? []).map((l, i) => ({
      key: i,
      productId: l.product.id,
      name: l.product.commercialName,
      baseUnit: l.product.baseUnit,
      unitId: l.unit ? String(l.unit.id) : '',
      quantity: l.quantity,
      unitPrice: l.unitPrice,
      discountPct: l.discountPct,
      vatRate: l.vatRate,
    })),
  )
  const [lineErrors, setLineErrors] = useState<Record<string, string>>({})
  const [supplierError, setSupplierError] = useState<string>()
  const [problem, setProblem] = useState<Problem>()
  const [duplicate, setDuplicate] = useState<string>()

  const update = (key: number, patch: Partial<LineState>) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)))

  const prefill = async (key: number, productId: number, unitId: string) => {
    if (supplierId === '') {
      return
    }
    try {
      const price = await fetchPrefill(Number(supplierId), productId, unitId === '' ? null : Number(unitId))
      update(key, { unitPrice: price.unitPrice, discountPct: price.discountPct, vatRate: price.vatRate })
    } catch {
      // the price stays editable; the server validates it on save
    }
  }

  const addProduct = (product: ProductSummary) => {
    if (lines.some((l) => l.productId === product.id)) {
      setDuplicate(t('purchasing.orders.duplicateProduct', { name: product.commercialName }))
      return
    }
    setDuplicate(undefined)
    const key = Math.max(-1, ...lines.map((l) => l.key)) + 1
    setLines((ls) => [...ls, { key, productId: product.id, name: product.commercialName, baseUnit: product.baseUnit, unitId: '', quantity: '', unitPrice: '', discountPct: '0', vatRate: '' }])
    void prefill(key, product.id, '')
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setProblem(undefined)
    setLineErrors({})
    setSupplierError(supplierId === '' ? t('fieldErrors.REQUIRED') : undefined)
    if (supplierId === '') {
      return
    }
    const decimal = (v: string) => (v.trim() === '' ? null : v.trim().replace(',', '.'))
    save.mutate(
      {
        id: order?.id,
        version: order?.version,
        order: {
          supplierId: Number(supplierId),
          expectedDate: expectedDate === '' ? null : expectedDate,
          lines: lines.map((l) => ({
            productId: l.productId,
            unitId: l.unitId === '' ? null : Number(l.unitId),
            quantity: decimal(l.quantity) ?? '',
            unitPrice: decimal(l.unitPrice) ?? '',
            discountPct: decimal(l.discountPct),
            vatRate: decimal(l.vatRate),
          })),
        },
      },
      {
        onSuccess: (saved) => navigate(`/purchase-orders/${saved.id}`, { replace: order !== undefined }),
        onError: (error) => {
          if (!(error instanceof ApiError)) {
            return
          }
          const placed: Record<string, string> = {}
          for (const e of error.problem.errors) {
            if (e.field === 'supplierId') {
              setSupplierError(t(`fieldErrors.${e.code}`))
              placed[e.field] = e.code
            } else if (e.field.startsWith('lines')) {
              placed[e.field] = t(`fieldErrors.${e.code}`)
            }
          }
          setLineErrors(placed)
          if (Object.keys(placed).length === 0) {
            setProblem(error.problem)
          }
        },
      },
    )
  }

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-4">
      {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
      {lineErrors.lines && <Alert tone="danger">{lineErrors.lines}</Alert>}
      <div className="flex flex-wrap gap-4">
        <div className="w-72">
          <SelectField
            label={t('purchasing.orders.supplier')}
            value={supplierId}
            error={supplierError}
            onChange={(e) => setSupplierId(e.target.value)}
            options={[{ value: '', label: '—' }, ...(suppliers.data?.content ?? []).map((s) => ({ value: String(s.id), label: s.name }))]}
          />
        </div>
        <div className="w-56">
          <TextField label={t('purchasing.orders.expectedDate')} type="date" value={expectedDate} onChange={(e) => setExpectedDate(e.target.value)} />
        </div>
      </div>
      <h2 className="text-lg font-semibold">{t('purchasing.orders.lines')}</h2>
      {lines.length === 0 ? (
        <p className="text-mute">{t('purchasing.orders.noLines')}</p>
      ) : (
        <div className="flex flex-col gap-3">
          {lines.map((line, i) => (
            <LineEditor
              key={line.key}
              line={line}
              errors={(field: LineField) => lineErrors[`lines[${i}].${field}`]}
              onChange={(patch) => update(line.key, patch)}
              onUnit={(unitId) => {
                update(line.key, { unitId })
                void prefill(line.key, line.productId, unitId)
              }}
              onRemove={() => setLines((ls) => ls.filter((l) => l.key !== line.key))}
            />
          ))}
        </div>
      )}
      {duplicate && <Alert tone="danger">{duplicate}</Alert>}
      {supplierId === '' ? (
        <p className="text-mute">{t('purchasing.orders.supplierFirst')}</p>
      ) : (
        <div className="max-w-xl">
          <ProductPicker onPick={addProduct} label={t('purchasing.orders.addProduct')} />
        </div>
      )}
      <div>
        <Button type="submit" disabled={save.isPending}>
          {t('purchasing.orders.saveDraft')}
        </Button>
      </div>
    </form>
  )
}

function LineEditor({ line, errors, onChange, onUnit, onRemove }: {
  line: LineState
  errors: (field: LineField) => string | undefined
  onChange: (patch: Partial<LineState>) => void
  onUnit: (unitId: string) => void
  onRemove: () => void
}) {
  const { t } = useTranslation()
  const units = useUnits(line.productId)
  return (
    <fieldset className="flex flex-wrap items-end gap-3 rounded-ctl border border-line p-3">
      <legend className="px-1 text-sm font-medium">{line.name}</legend>
      {errors('productId') && <p className="w-full text-sm text-danger">{errors('productId')}</p>}
      <div className="w-40">
        <SelectField
          label={t('purchasing.orders.unit')}
          value={line.unitId}
          error={errors('unitId')}
          onChange={(e) => onUnit(e.target.value)}
          options={[{ value: '', label: line.baseUnit }, ...(units.data ?? []).map((u) => ({ value: String(u.id), label: u.name }))]}
        />
      </div>
      <div className="w-28">
        <TextField label={t('purchasing.orders.quantity')} inputMode="decimal" value={line.quantity} error={errors('quantity')} onChange={(e) => onChange({ quantity: e.target.value })} />
      </div>
      <div className="w-36">
        <TextField label={t('purchasing.orders.unitPrice')} inputMode="decimal" value={line.unitPrice} error={errors('unitPrice')} onChange={(e) => onChange({ unitPrice: e.target.value })} />
      </div>
      <div className="w-28">
        <TextField label={t('purchasing.orders.discountPct')} inputMode="decimal" value={line.discountPct} error={errors('discountPct')} onChange={(e) => onChange({ discountPct: e.target.value })} />
      </div>
      <div className="w-28">
        <TextField label={t('purchasing.orders.vatRate')} inputMode="decimal" value={line.vatRate} error={errors('vatRate')} onChange={(e) => onChange({ vatRate: e.target.value })} />
      </div>
      <Button type="button" variant="secondary" onClick={onRemove} aria-label={t('purchasing.orders.removeLine', { name: line.name })}>
        {t('purchasing.orders.remove')}
      </Button>
    </fieldset>
  )
}
