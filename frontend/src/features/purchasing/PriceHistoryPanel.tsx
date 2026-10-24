import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Pager } from '../../components/Pager'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatUnitPrice } from '../../lib/format'
import { ProductPicker } from './ProductPicker'
import { useAddPrice, useSupplierPrices } from './supplierApi'

/**
 * A supplier's price history, newest first (BR-PURCH-008): price per base unit and discount; an entry of a
 * cancelled receipt is struck through (ADR-106); a manual entry with `supplier.manage` (ADR-100).
 */
export function PriceHistoryPanel({ supplierId, manage }: { supplierId: number; manage: boolean }) {
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const prices = useSupplierPrices(supplierId, page)
  return (
    <div className="flex flex-col gap-3">
      <h2 className="text-lg font-semibold">{t('purchasing.prices.title')}</h2>
      {prices.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : prices.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(prices.error.problem, t)}</Alert>
      ) : !prices.data || prices.data.content.length === 0 ? (
        <p className="text-mute">{t('purchasing.prices.empty')}</p>
      ) : (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('catalog.products.name')}</th>
                <th className="text-end">{t('purchasing.prices.unitPrice')}</th>
                <th className="text-end">{t('purchasing.prices.discountPct')}</th>
                <th className="text-start ps-4">{t('purchasing.prices.validFrom')}</th>
                <th className="text-start">{t('purchasing.prices.origin')}</th>
              </tr>
            </thead>
            <tbody>
              {prices.data.content.map((p) => (
                <tr key={p.id} className={`border-b border-line ${p.cancelled ? 'line-through text-mute' : ''}`}>
                  <td className="py-2">
                    {p.product.commercialName} <code className="text-mute">{p.product.sku}</code>
                  </td>
                  <td className="text-end">{`${formatUnitPrice(p.unitPrice)} / ${p.product.baseUnit}`}</td>
                  <td className="text-end">{p.discountPct} %</td>
                  <td className="ps-4">{formatDate(p.validFrom)}</td>
                  <td>
                    {p.cancelled
                      ? t('purchasing.prices.cancelledReceipt')
                      : t(p.receiptId === null ? 'purchasing.prices.manual' : 'purchasing.prices.receipt')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={prices.data.page} onPage={setPage} />
        </>
      )}
      {manage && <ManualPrice supplierId={supplierId} />}
    </div>
  )
}

function ManualPrice({ supplierId }: { supplierId: number }) {
  const { t } = useTranslation()
  const add = useAddPrice(supplierId)
  const [product, setProduct] = useState<{ id: number; commercialName: string }>()
  const [unitPrice, setUnitPrice] = useState('')
  const [discount, setDiscount] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!product) {
      return
    }
    if (unitPrice.trim() === '') {
      setErrors({ unitPrice: t('fieldErrors.REQUIRED') })
      return
    }
    setErrors({})
    add.mutate(
      { productId: product.id, unitPrice: unitPrice.trim().replace(',', '.'), discountPct: discount.trim() === '' ? null : discount.trim().replace(',', '.'), validFrom: null },
      {
        onSuccess: () => {
          setProduct(undefined)
          setUnitPrice('')
          setDiscount('')
        },
        onError: (error) => {
          if (error instanceof ApiError) {
            setErrors(Object.fromEntries(error.problem.errors.map((e) => [e.field, t(`fieldErrors.${e.code}`)])))
          }
        },
      },
    )
  }

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-3 rounded-ctl border border-line p-4">
      <h3 className="font-medium">{t('purchasing.prices.add')}</h3>
      {product ? (
        <p className="text-sm">
          {product.commercialName}{' '}
          <Button type="button" variant="secondary" onClick={() => setProduct(undefined)}>
            {t('purchasing.changeProduct')}
          </Button>
        </p>
      ) : (
        <ProductPicker onPick={setProduct} />
      )}
      {add.error instanceof ApiError && add.error.problem.errors.length === 0 && <Alert tone="danger">{problemMessage(add.error.problem, t)}</Alert>}
      <div className="flex flex-wrap gap-4 items-end">
        <TextField label={t('purchasing.prices.unitPrice')} inputMode="decimal" value={unitPrice} error={errors.unitPrice} onChange={(e) => setUnitPrice(e.target.value)} />
        <TextField label={t('purchasing.prices.discountPct')} inputMode="decimal" value={discount} error={errors.discountPct} onChange={(e) => setDiscount(e.target.value)} />
        <Button type="submit" disabled={!product || add.isPending}>
          {t('purchasing.prices.addButton')}
        </Button>
      </div>
    </form>
  )
}
