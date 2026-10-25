import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { hasPermission, useCurrentUser } from '../auth'
import { useCustomerCategories, useSaveCategory, type CustomerCategory } from './customerApi'

/**
 * `/customer-categories` (frontend.md §4.6, ADR-112): categories with their default discount, never deleted; with
 * `customer.price.manage`, add, rename and change the discount (audited by the server; documents already issued keep
 * their discount).
 */
export function CustomerCategoriesPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const manage = hasPermission(current, 'customer.price.manage')
  const categories = useCustomerCategories()
  const [editing, setEditing] = useState<number>()

  return (
    <section className="flex flex-col gap-4 max-w-3xl">
      <h1 className="text-2xl font-semibold">{t('customers.categories.title')}</h1>
      <p className="text-sm text-mute">{t('customers.categories.explain')}</p>
      {categories.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : categories.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(categories.error.problem, t)}</Alert>
      ) : !categories.data || categories.data.length === 0 ? (
        <p className="text-mute">{t('customers.categories.empty')}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {categories.data.map((c) => (
            <li key={c.id} className="rounded-ctl border border-line px-3 py-2 text-sm">
              {editing === c.id ? (
                <CategoryForm category={c} onDone={() => setEditing(undefined)} />
              ) : (
                <div className="flex items-center gap-3">
                  <span className="font-medium">{c.name}</span>
                  <span className="text-mute">{t('customers.categories.discountOf', { pct: c.discountPct })}</span>
                  {manage && (
                    <Button variant="secondary" className="ms-auto" onClick={() => setEditing(c.id)} aria-label={t('customers.categories.editOf', { name: c.name })}>
                      {t('customers.categories.edit')}
                    </Button>
                  )}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {manage && (
        <div className="flex flex-col gap-2">
          <h2 className="text-lg font-semibold">{t('customers.categories.add')}</h2>
          <CategoryForm onDone={() => undefined} />
        </div>
      )}
    </section>
  )
}

function CategoryForm({ category, onDone }: { category?: CustomerCategory; onDone: () => void }) {
  const { t } = useTranslation()
  const save = useSaveCategory()
  const [name, setName] = useState(category?.name ?? '')
  const [discount, setDiscount] = useState(category?.discountPct ?? '0')
  const [errors, setErrors] = useState<{ name?: string; discountPct?: string }>({})
  const [problem, setProblem] = useState<string>()

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setProblem(undefined)
    if (name.trim() === '') {
      setErrors({ name: t('fieldErrors.REQUIRED') })
      return
    }
    setErrors({})
    save.mutate(
      { id: category?.id, name: name.trim(), discountPct: discount.trim().replace(',', '.'), version: category?.version },
      {
        onSuccess: () => {
          if (!category) {
            setName('')
            setDiscount('0')
          }
          onDone()
        },
        onError: (e) => {
          if (!(e instanceof ApiError)) {
            return
          }
          const next: { name?: string; discountPct?: string } = {}
          for (const v of e.problem.errors) {
            if (v.field === 'name' || v.field === 'discountPct') {
              next[v.field] = t(`fieldErrors.${v.code}`)
            }
          }
          if (e.problem.code === 'DUPLICATE_RESOURCE') {
            next.name = t('fieldErrors.DUPLICATE')
          }
          setErrors(next)
          if (Object.keys(next).length === 0) {
            setProblem(problemMessage(e.problem, t))
          }
        },
      },
    )
  }

  return (
    <form onSubmit={submit} noValidate className="flex flex-wrap items-end gap-3">
      <div className="w-64">
        <TextField label={t('customers.categories.name')} value={name} error={errors.name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="w-36">
        <TextField label={t('customers.categories.discountPct')} inputMode="decimal" dir="ltr" value={discount} error={errors.discountPct} onChange={(e) => setDiscount(e.target.value)} />
      </div>
      <Button type="submit" disabled={save.isPending}>
        {t('customers.save')}
      </Button>
      {category && (
        <Button variant="secondary" onClick={onDone}>
          {t('customers.cancel')}
        </Button>
      )}
      {problem && (
        <div className="w-full">
          <Alert tone="danger">{problem}</Alert>
        </div>
      )}
    </form>
  )
}
