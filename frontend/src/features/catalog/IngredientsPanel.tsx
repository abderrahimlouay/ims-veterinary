import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { problemMessage } from '../../lib/errors'
import { useReplaceIngredients, type ProductDetail } from './productApi'
import { useReferenceList } from './referenceApi'

interface Row {
  key: number
  ingredientId: string
  strength: string
}

/** The product's active ingredients and strengths (FR-PROD-004); edited as a whole set with `product.update`. */
export function IngredientsPanel({ product, editable }: { product: ProductDetail; editable: boolean }) {
  const { t } = useTranslation()
  const available = useReferenceList('active-ingredients')
  const replace = useReplaceIngredients(product.id)
  const [rows, setRows] = useState<Row[]>(() =>
    product.ingredients.map((i, key) => ({ key, ingredientId: String(i.ingredient.id), strength: i.strength ?? '' })),
  )
  const [saved, setSaved] = useState(false)
  const [nextKey, setNextKey] = useState(rows.length)

  if (!editable) {
    return product.ingredients.length === 0 ? (
      <p className="text-mute">{t('catalog.ingredients.none')}</p>
    ) : (
      <ul className="flex flex-col gap-1">
        {product.ingredients.map((i) => (
          <li key={i.ingredient.id}>
            {i.ingredient.name}
            {i.strength && <span className="text-mute"> — {i.strength}</span>}
          </li>
        ))}
      </ul>
    )
  }

  const update = (key: number, changes: Partial<Row>) => {
    setSaved(false)
    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, ...changes } : r)))
  }
  const save = () =>
    replace.mutate(
      rows
        .filter((r) => r.ingredientId !== '')
        .map((r) => ({ ingredientId: Number(r.ingredientId), strength: r.strength.trim() === '' ? null : r.strength })),
      { onSuccess: () => setSaved(true) },
    )

  return (
    <div className="flex flex-col gap-3 max-w-xl">
      {saved && <Alert tone="ok">{t('admin.saved')}</Alert>}
      {replace.error instanceof ApiError && (
        <Alert tone="danger">
          {replace.error.problem.errors[0]
            ? t(`catalog.ingredients.errors.${replace.error.problem.errors[0].code}`, {
                defaultValue: problemMessage(replace.error.problem, t),
              })
            : problemMessage(replace.error.problem, t)}
        </Alert>
      )}
      {rows.length === 0 && <p className="text-mute">{t('catalog.ingredients.none')}</p>}
      {rows.map((row, index) => (
        <div key={row.key} className="flex items-end gap-2">
          <label className="flex flex-col gap-1 flex-1 text-sm font-medium">
            {t('catalog.ingredients.ingredient', { n: index + 1 })}
            <select
              value={row.ingredientId}
              onChange={(e) => update(row.key, { ingredientId: e.target.value })}
              className="h-10 px-2 rounded-ctl bg-surface border border-control text-sm font-normal"
            >
              <option value="">—</option>
              {(available.data ?? []).map((i) => (
                <option key={i.id} value={i.id}>
                  {i.name}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 w-36 text-sm font-medium">
            {t('catalog.ingredients.strength', { n: index + 1 })}
            <input
              value={row.strength}
              maxLength={60}
              onChange={(e) => update(row.key, { strength: e.target.value })}
              className="h-10 px-3 rounded-ctl bg-surface border border-control text-sm font-normal"
            />
          </label>
          <Button variant="secondary" onClick={() => setRows((rs) => rs.filter((r) => r.key !== row.key))}>
            {t('catalog.ingredients.remove', { n: index + 1 })}
          </Button>
        </div>
      ))}
      <div className="flex gap-3">
        <Button
          variant="secondary"
          onClick={() => {
            setRows((rs) => [...rs, { key: nextKey, ingredientId: '', strength: '' }])
            setNextKey((k) => k + 1)
          }}
        >
          {t('catalog.ingredients.add')}
        </Button>
        <Button disabled={replace.isPending} onClick={save}>
          {t('admin.save')}
        </Button>
      </div>
    </div>
  )
}
