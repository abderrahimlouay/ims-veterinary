import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { businessToday, parseExpiry } from '../../lib/expiry'
import { formatDate, formatQuantity } from '../../lib/format'
import { useCurrentUser } from '../auth'
import { useProduct, useProducts, useUnits, type ProductSummary } from '../catalog'
import { usePublicSettings } from '../settings'
import type { LotDetail } from './lotApi'
import { useOpeningStock } from './stockApi'

type Field = 'lotNumber' | 'expiryDate' | 'quantity' | 'unitId' | 'unitCost'
type FieldErrors = Partial<Record<Field, string>>
const FIELDS: readonly Field[] = ['lotNumber', 'expiryDate', 'quantity', 'unitId', 'unitCost']

/**
 * `/stock/opening-stock/new` (frontend.md §4.3, W10a, ADR-082…084): while `opening_stock_open` is true, product, lot
 * number, expiry (a date or `MM/AAAA`, ADR-055), quantity in a unit of the product and unit cost prefilled from the
 * purchase price; an expired lot is accepted with a warning. When the period is closed the page says that stock
 * found now goes through an inventory count.
 */
export function OpeningStockPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const settings = usePublicSettings(current !== null && current !== undefined)
  const [product, setProduct] = useState<ProductSummary>()
  const [entered, setEntered] = useState<LotDetail>()

  return (
    <section className="flex flex-col gap-4 max-w-2xl">
      <h1 className="text-2xl font-semibold">{t('stock.opening.title')}</h1>
      {settings.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : settings.data?.openingStockOpen === false ? (
        <Alert tone="danger">{t('errors.OPENING_STOCK_CLOSED')}</Alert>
      ) : (
        <>
          {entered && (
            <Alert tone="ok">
              {t('stock.opening.done', { lotNumber: entered.lotNumber, quantity: `${formatQuantity(entered.quantityOnHand)} ${entered.product.baseUnit}` })}{' '}
              <Link to={`/lots/${entered.id}`} className="underline">
                {t('stock.opening.openLot')}
              </Link>
            </Alert>
          )}
          {product ? (
            <OpeningForm
              key={product.id}
              productId={product.id}
              onChangeProduct={() => setProduct(undefined)}
              onDone={(lot) => setEntered(lot)}
            />
          ) : (
            <ProductPicker onPick={(p) => setProduct(p)} />
          )}
        </>
      )}
    </section>
  )
}

function ProductPicker({ onPick }: { onPick: (product: ProductSummary) => void }) {
  const { t } = useTranslation()
  const [q, setQ] = useState('')
  const products = useProducts({ q, categoryId: '', manufacturerId: '', ingredientId: '', speciesId: '', includeArchived: false, inStockOnly: false, page: 0 }, 10)
  return (
    <div className="flex flex-col gap-3">
      <TextField label={t('stock.opening.findProduct')} type="search" value={q} onChange={(e) => setQ(e.target.value)} />
      {q.trim() !== '' &&
        (products.isPending ? (
          <p role="status" className="text-mute">
            {t('app.loading')}
          </p>
        ) : products.error instanceof ApiError ? (
          <Alert tone="danger">{problemMessage(products.error.problem, t)}</Alert>
        ) : !products.data || products.data.content.length === 0 ? (
          <p className="text-mute">{t('catalog.products.noMatch')}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {products.data.content.map((p) => (
              <li key={p.id} className="flex items-center gap-3 rounded-ctl border border-line px-3 py-2 text-sm">
                <code>{p.sku}</code>
                <span>{p.commercialName}</span>
                <Button variant="secondary" className="ms-auto" onClick={() => onPick(p)} aria-label={t('stock.opening.pickProduct', { name: p.commercialName })}>
                  {t('stock.adjustment.pick')}
                </Button>
              </li>
            ))}
          </ul>
        ))}
    </div>
  )
}

function OpeningForm({ productId, onChangeProduct, onDone }: {
  productId: number
  onChangeProduct: () => void
  onDone: (lot: LotDetail) => void
}) {
  const { t } = useTranslation()
  const product = useProduct(productId)
  const units = useUnits(productId)
  const opening = useOpeningStock()
  const [lotNumber, setLotNumber] = useState('')
  const [expiry, setExpiry] = useState('')
  const [quantity, setQuantity] = useState('')
  const [unitId, setUnitId] = useState('')
  const [unitCost, setUnitCost] = useState<string>()
  const [errors, setErrors] = useState<FieldErrors>({})
  const [problem, setProblem] = useState<Problem>()

  if (product.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (product.error instanceof ApiError || !product.data) {
    return <Alert tone="danger">{product.error instanceof ApiError ? problemMessage(product.error.problem, t) : t('errors.UNKNOWN')}</Alert>
  }
  const data = product.data
  const cost = unitCost ?? data.purchasePrice
  const expiryDate = parseExpiry(expiry)
  const expired = expiryDate !== null && expiryDate < businessToday()

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const local: FieldErrors = {}
    if (lotNumber.trim() === '') {
      local.lotNumber = t('fieldErrors.REQUIRED')
    }
    if (expiryDate === null) {
      local.expiryDate = t(expiry.trim() === '' ? 'fieldErrors.REQUIRED' : 'fieldErrors.INVALID_FORMAT')
    }
    if (quantity.trim() === '') {
      local.quantity = t('fieldErrors.REQUIRED')
    }
    if (cost.trim() === '') {
      local.unitCost = t('fieldErrors.REQUIRED')
    }
    setErrors(local)
    setProblem(undefined)
    if (Object.keys(local).length > 0 || expiryDate === null) {
      return
    }
    opening.mutate(
      {
        productId,
        lotNumber: lotNumber.trim(),
        expiryDate,
        quantity: quantity.trim().replace(',', '.'),
        unitId: unitId === '' ? null : Number(unitId),
        unitCost: cost.trim().replace(',', '.'),
      },
      {
        onSuccess: (result) => {
          onDone(result.lot)
          setLotNumber('')
          setExpiry('')
          setQuantity('')
        },
        onError: (error) => {
          if (!(error instanceof ApiError)) {
            return
          }
          const server: FieldErrors = {}
          for (const e of error.problem.errors) {
            const field = FIELDS.find((f) => f === e.field)
            if (field !== undefined) {
              server[field] = t(`fieldErrors.${e.code}`)
            }
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
      <div className="flex items-center gap-3 rounded-ctl border border-line p-3 text-sm">
        <code>{data.sku}</code>
        <span className="font-medium">{data.commercialName}</span>
        <button type="button" className="ms-auto underline text-brand" onClick={onChangeProduct}>
          {t('stock.opening.changeProduct')}
        </button>
      </div>
      <TextField label={t('lots.lotNumber')} value={lotNumber} maxLength={60} error={errors.lotNumber} onChange={(e) => setLotNumber(e.target.value)} />
      <TextField
        label={t('stock.opening.expiry')}
        hint={expiryDate !== null ? formatDate(expiryDate) : t('stock.opening.expiryHint')}
        value={expiry}
        error={errors.expiryDate}
        onChange={(e) => setExpiry(e.target.value)}
      />
      {expired && <Alert tone="danger">{t('stock.opening.expired')}</Alert>}
      <div className="grid grid-cols-2 gap-3">
        <TextField label={t('stock.opening.quantity')} inputMode="decimal" value={quantity} error={errors.quantity} onChange={(e) => setQuantity(e.target.value)} />
        <SelectField
          label={t('stock.opening.unit')}
          value={unitId}
          error={errors.unitId}
          onChange={(e) => setUnitId(e.target.value)}
          options={[
            { value: '', label: data.baseUnit },
            ...(units.data ?? []).map((u) => ({ value: String(u.id), label: t('stock.opening.unitOption', { name: u.name, factor: u.factorToBase, base: data.baseUnit }) })),
          ]}
        />
      </div>
      <TextField
        label={t('stock.opening.unitCost', { unit: data.baseUnit })}
        hint={t('stock.opening.unitCostHint')}
        inputMode="decimal"
        value={cost}
        error={errors.unitCost}
        onChange={(e) => setUnitCost(e.target.value)}
      />
      {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
      <div>
        <Button type="submit" disabled={opening.isPending}>
          {t('stock.opening.submit')}
        </Button>
      </div>
    </form>
  )
}
