import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { referenceLabel, useProducts, useReferenceList, type ProductSummary } from '../catalog'
import { useOpenCount, type CountType } from './countApi'

type Scope = 'category' | 'products'

/**
 * `/inventory-counts/new` (frontend.md §4.4, ADR-073): a full count (every lot with stock; one open at a time,
 * BR-INV-007) or a cycle count of one therapeutic category or of a list of products.
 */
export function NewCountPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const open = useOpenCount()
  const categories = useReferenceList('categories')
  const [type, setType] = useState<CountType>('full')
  const [scope, setScope] = useState<Scope>('category')
  const [categoryId, setCategoryId] = useState('')
  const [products, setProducts] = useState<ProductSummary[]>([])
  const [notes, setNotes] = useState('')
  const [scopeError, setScopeError] = useState<string>()
  const [problem, setProblem] = useState<Problem>()

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setProblem(undefined)
    setScopeError(undefined)
    if (type === 'cycle' && (scope === 'category' ? categoryId === '' : products.length === 0)) {
      setScopeError(t('counts.scopeRequired'))
      return
    }
    open.mutate(
      {
        type,
        ...(type === 'cycle' && scope === 'category' ? { categoryId: Number(categoryId) } : {}),
        ...(type === 'cycle' && scope === 'products' ? { productIds: products.map((p) => p.id) } : {}),
        ...(notes.trim() === '' ? {} : { notes: notes.trim() }),
      },
      {
        onSuccess: (count) => void navigate(`/inventory-counts/${count.id}`),
        onError: (error) => error instanceof ApiError && setProblem(error.problem),
      },
    )
  }

  return (
    <section className="flex flex-col gap-4 max-w-2xl">
      <h1 className="text-2xl font-semibold">{t('counts.newTitle')}</h1>
      <form onSubmit={submit} noValidate className="flex flex-col gap-4">
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium mb-1">{t('counts.type')}</legend>
          {(['full', 'cycle'] as const).map((value) => (
            <label key={value} className="flex items-center gap-2 text-sm">
              <input type="radio" name="type" checked={type === value} onChange={() => setType(value)} />
              {t(`counts.typeHint.${value}`)}
            </label>
          ))}
        </fieldset>
        {type === 'cycle' && (
          <fieldset className="flex flex-col gap-3">
            <legend className="text-sm font-medium mb-1">{t('counts.scope')}</legend>
            <div className="flex gap-4">
              {(['category', 'products'] as const).map((value) => (
                <label key={value} className="flex items-center gap-2 text-sm">
                  <input type="radio" name="scope" checked={scope === value} onChange={() => setScope(value)} />
                  {t(`counts.scopes.${value}`)}
                </label>
              ))}
            </div>
            {scope === 'category' ? (
              <SelectField
                label={t('catalog.products.category')}
                value={categoryId}
                error={scopeError}
                onChange={(e) => setCategoryId(e.target.value)}
                options={[{ value: '', label: '—' }, ...(categories.data ?? []).map((c) => ({ value: String(c.id), label: referenceLabel(c, 'categories', t) }))]}
              />
            ) : (
              <ProductScope products={products} onChange={setProducts} error={scopeError} />
            )}
          </fieldset>
        )}
        <TextField label={t('counts.notes')} value={notes} maxLength={1000} onChange={(e) => setNotes(e.target.value)} />
        {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
        <div>
          <Button type="submit" disabled={open.isPending}>
            {t('counts.open')}
          </Button>
        </div>
      </form>
    </section>
  )
}

function ProductScope({ products, onChange, error }: {
  products: ProductSummary[]
  onChange: (products: ProductSummary[]) => void
  error?: string
}) {
  const { t } = useTranslation()
  const [q, setQ] = useState('')
  const found = useProducts({ q, categoryId: '', manufacturerId: '', ingredientId: '', speciesId: '', includeArchived: false, inStockOnly: false, page: 0 }, 10)
  const chosen = new Set(products.map((p) => p.id))
  return (
    <div className="flex flex-col gap-2">
      <TextField label={t('counts.findProduct')} type="search" value={q} error={error} onChange={(e) => setQ(e.target.value)} />
      {q.trim() !== '' && found.data && (
        <ul className="flex flex-col gap-1">
          {found.data.content.filter((p) => !chosen.has(p.id)).map((p) => (
            <li key={p.id} className="flex items-center gap-3 text-sm">
              <code>{p.sku}</code>
              <span>{p.commercialName}</span>
              <Button variant="secondary" className="ms-auto" onClick={() => onChange([...products, p])} aria-label={t('counts.addProduct', { name: p.commercialName })}>
                {t('counts.add')}
              </Button>
            </li>
          ))}
        </ul>
      )}
      {products.length > 0 && (
        <ul aria-label={t('counts.chosenProducts')} className="flex flex-wrap gap-2">
          {products.map((p) => (
            <li key={p.id} className="flex items-center gap-2 rounded-ctl border border-line px-2 py-1 text-sm">
              {p.commercialName}
              <button
                type="button"
                className="text-danger underline"
                aria-label={t('counts.removeProduct', { name: p.commercialName })}
                onClick={() => onChange(products.filter((x) => x.id !== p.id))}
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
