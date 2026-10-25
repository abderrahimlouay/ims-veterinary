import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatUnitPrice } from '../../lib/format'
import type { ProductSummary } from '../catalog'
import { ProductPicker } from '../purchasing/ProductPicker'
import { useCustomerPrices, useRemoveCustomerPrice, useSetCustomerPrice, type CustomerPrice } from './customerApi'

/**
 * The Prices tab (FR-SALE-010, ADR-111): the customer's own prices per base unit, by product name. With
 * `customer.price.manage`: add one, change one, remove one, each on its own (the server audits each change).
 */
export function CustomerPricesPanel({ customerId, manage }: { customerId: number; manage: boolean }) {
  const { t } = useTranslation()
  const prices = useCustomerPrices(customerId)
  const remove = useRemoveCustomerPrice(customerId)
  const [editing, setEditing] = useState<number>()
  const [adding, setAdding] = useState<ProductSummary>()

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-mute">{t('customers.prices.explain')}</p>
      {remove.error instanceof ApiError && <Alert tone="danger">{problemMessage(remove.error.problem, t)}</Alert>}
      {prices.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : prices.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(prices.error.problem, t)}</Alert>
      ) : !prices.data || prices.data.length === 0 ? (
        <p className="text-mute">{t('customers.prices.empty')}</p>
      ) : (
        <table className="w-full text-sm border-collapse">
          <thead>
            <tr className="border-b border-line">
              <th className="text-start py-2">{t('catalog.products.name')}</th>
              <th className="text-end">{t('customers.prices.unitPrice')}</th>
              {manage && <th className="text-end">{t('customers.prices.actions')}</th>}
            </tr>
          </thead>
          <tbody>
            {prices.data.map((p) => (
              <tr key={p.product.id} className="border-b border-line">
                <td className="py-2">
                  {p.product.commercialName} <code className="text-mute">{p.product.sku}</code>
                  {p.product.archived && <span className="ms-2 text-mute">{t('customers.prices.archived')}</span>}
                </td>
                <td className="text-end">
                  {editing === p.product.id ? (
                    <PriceForm customerId={customerId} productId={p.product.id} current={p} onDone={() => setEditing(undefined)} />
                  ) : (
                    `${formatUnitPrice(p.unitPrice)} / ${p.product.baseUnit}`
                  )}
                </td>
                {manage && (
                  <td className="text-end">
                    {editing !== p.product.id && (
                      <span className="inline-flex gap-2">
                        {!p.product.archived && (
                          <Button variant="secondary" onClick={() => setEditing(p.product.id)} aria-label={t('customers.prices.changeOf', { name: p.product.commercialName })}>
                            {t('customers.prices.change')}
                          </Button>
                        )}
                        <Button
                          variant="secondary"
                          disabled={remove.isPending}
                          onClick={() => remove.mutate({ productId: p.product.id, version: p.version })}
                          aria-label={t('customers.prices.removeOf', { name: p.product.commercialName })}
                        >
                          {t('customers.prices.remove')}
                        </Button>
                      </span>
                    )}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {manage && (
        <div className="flex flex-col gap-3 max-w-xl">
          <h3 className="font-semibold">{t('customers.prices.add')}</h3>
          {adding ? (
            <div className="flex flex-col gap-2">
              <p className="text-sm">
                {adding.commercialName} <code className="text-mute">{adding.sku}</code>
              </p>
              <PriceForm customerId={customerId} productId={adding.id} onDone={() => setAdding(undefined)} />
            </div>
          ) : (
            <ProductPicker onPick={setAdding} label={t('customers.prices.findProduct')} />
          )}
        </div>
      )}
    </div>
  )
}

function PriceForm({ customerId, productId, current, onDone }: { customerId: number; productId: number; current?: CustomerPrice; onDone: () => void }) {
  const { t } = useTranslation()
  const save = useSetCustomerPrice(customerId)
  const [value, setValue] = useState(current?.unitPrice ?? '')
  const [error, setError] = useState<string>()

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const price = value.trim().replace(',', '.')
    if (price === '') {
      setError(t('fieldErrors.REQUIRED'))
      return
    }
    setError(undefined)
    save.mutate(
      { productId, unitPrice: price, version: current?.version ?? null },
      {
        onSuccess: onDone,
        onError: (e) => {
          if (e instanceof ApiError) {
            const field = e.problem.errors.find((v) => v.field === 'unitPrice')
            setError(field ? t(`fieldErrors.${field.code}`) : problemMessage(e.problem, t))
          }
        },
      },
    )
  }

  return (
    <form onSubmit={submit} noValidate className="inline-flex items-end gap-2 text-start">
      <TextField label={t('customers.prices.unitPrice')} inputMode="decimal" dir="ltr" value={value} error={error} onChange={(e) => setValue(e.target.value)} />
      <Button type="submit" disabled={save.isPending}>
        {t('customers.prices.save')}
      </Button>
      <Button variant="secondary" onClick={onDone}>
        {t('customers.cancel')}
      </Button>
    </form>
  )
}
