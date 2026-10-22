import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatFactor, formatUnitPrice } from '../../lib/format'
import { placeServerErrors } from '../../lib/formErrors'
import { useSaveUnit, useUnits, type ProductDetail, type ProductUnit } from './productApi'

const schema = z.object({
  name: z.string().trim().min(1, 'validation.required').max(40, 'validation.tooLong'),
  factorToBase: z.string().trim().regex(/^\d+(\.\d{1,4})?$/, 'fieldErrors.SCALE'),
  salePrice: z.union([z.literal(''), z.string().trim().regex(/^\d+(\.\d{1,2})?$/, 'fieldErrors.SCALE')]),
  sellable: z.boolean(),
})
type Form = z.infer<typeof schema>
const FIELDS = ['name', 'factorToBase', 'salePrice'] as const
const EMPTY: Form = { name: '', factorToBase: '', salePrice: '', sellable: true }

function toForm(unit?: ProductUnit): Form {
  return unit
    ? { name: unit.name, factorToBase: unit.factorToBase, salePrice: unit.salePrice ?? '', sellable: unit.sellable }
    : EMPTY
}

/**
 * Packaging units of a product (FR-PROD-006/007, frontend.md §4.1): content in base units, and an optional
 * price per unit, else derived from the base price (ADR-063). The unit price needs `price.edit`; without it the
 * stored price is sent back unchanged. A factor in use is refused by the server (BR-UNIT-004).
 */
export function UnitsPanel({ product, editable, canEditPrices }: { product: ProductDetail; editable: boolean; canEditPrices: boolean }) {
  const { t } = useTranslation()
  const units = useUnits(product.id)
  const [editing, setEditing] = useState<ProductUnit>()

  if (units.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (units.error instanceof ApiError) {
    return <Alert tone="danger">{problemMessage(units.error.problem, t)}</Alert>
  }
  const list = units.data ?? []
  return (
    <div className="flex flex-col gap-6 max-w-3xl">
      {list.length === 0 ? (
        <p className="text-mute">{t('catalog.units.empty', { baseUnit: product.baseUnit })}</p>
      ) : (
        <table className="text-sm border-collapse">
          <thead>
            <tr className="border-b border-line">
              <th className="text-start py-2 pe-6">{t('catalog.units.name')}</th>
              <th className="text-start pe-6">{t('catalog.units.content')}</th>
              <th className="text-end pe-6">{t('catalog.units.price')}</th>
              <th className="text-start pe-6">{t('catalog.units.sellable')}</th>
              {editable && <th />}
            </tr>
          </thead>
          <tbody>
            {list.map((u) => (
              <tr key={u.id} className="border-b border-line">
                <td className="py-2 pe-6">{u.name}</td>
                <td className="pe-6">{`${formatFactor(u.factorToBase)} ${product.baseUnit}`}</td>
                <td className="text-end pe-6">
                  {u.salePrice === null
                    ? t('catalog.units.derived', { price: formatUnitPrice(u.effectiveSalePrice) })
                    : formatUnitPrice(u.salePrice)}
                </td>
                <td className="pe-6">{u.sellable ? t('catalog.units.yes') : t('catalog.units.no')}</td>
                {editable && (
                  <td>
                    <Button variant="secondary" onClick={() => setEditing(u)}>
                      {t('catalog.units.edit', { name: u.name })}
                    </Button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {editable && (
        <UnitForm
          key={editing ? `${editing.id}-${editing.version}` : 'new'}
          product={product}
          unit={editing}
          canEditPrices={canEditPrices}
          onDone={() => setEditing(undefined)}
        />
      )}
    </div>
  )
}

function UnitForm({ product, unit, canEditPrices, onDone }: {
  product: ProductDetail
  unit?: ProductUnit
  canEditPrices: boolean
  onDone: () => void
}) {
  const { t } = useTranslation()
  const save = useSaveUnit(product.id)
  const [problem, setProblem] = useState<Problem>()
  const [saved, setSaved] = useState(false)
  const { register, handleSubmit, formState, setError, reset } = useForm<Form>({ resolver: zodResolver(schema), defaultValues: toForm(unit) })
  const fieldError = (name: keyof Form) => {
    const message = formState.errors[name]?.message
    return message === undefined ? undefined : t(message)
  }

  const onSubmit = handleSubmit((v) => {
    setProblem(undefined)
    setSaved(false)
    const salePrice = canEditPrices ? (v.salePrice === '' ? null : v.salePrice) : (unit?.salePrice ?? null)
    save.mutate(
      { unitId: unit?.id, unit: { name: v.name, factorToBase: v.factorToBase, salePrice, sellable: v.sellable, version: unit?.version } },
      {
        onSuccess: () => {
          setSaved(true)
          if (unit === undefined) {
            reset(EMPTY)
          }
        },
        onError: (error) => setProblem(placeServerErrors(error, FIELDS, setError)),
      },
    )
  })

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3">
      <fieldset className="flex flex-col gap-3">
        <legend className="font-semibold mb-1">
          {unit ? t('catalog.units.editTitle', { name: unit.name }) : t('catalog.units.addTitle')}
        </legend>
        {saved && <Alert tone="ok">{t('admin.saved')}</Alert>}
        {problem && <Alert tone="danger">{problem.code === 'CONCURRENT_MODIFICATION' ? t('catalog.units.stale') : problemMessage(problem, t)}</Alert>}
        <div className="grid grid-cols-3 gap-4">
          <TextField label={t('catalog.units.nameField')} error={fieldError('name')} {...register('name')} />
          <TextField
            label={t('catalog.units.factorField', { baseUnit: product.baseUnit })}
            inputMode="decimal"
            error={fieldError('factorToBase')}
            {...register('factorToBase')}
          />
          <TextField
            label={t('catalog.units.priceField')}
            inputMode="decimal"
            disabled={!canEditPrices}
            error={fieldError('salePrice')}
            {...register('salePrice')}
          />
        </div>
        <p className="text-sm text-mute">{canEditPrices ? t('catalog.units.priceHint') : t('catalog.products.pricesReadOnly')}</p>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" {...register('sellable')} />
          {t('catalog.units.sellableField')}
        </label>
      </fieldset>
      <div className="flex gap-3">
        <Button type="submit" disabled={save.isPending}>
          {unit ? t('admin.save') : t('catalog.units.add')}
        </Button>
        {unit && (
          <Button variant="secondary" onClick={onDone}>
            {t('catalog.units.cancel')}
          </Button>
        )}
      </div>
    </form>
  )
}
