import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useNavigate, useParams } from 'react-router'
import { z } from 'zod'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { placeServerErrors } from '../../lib/formErrors'
import { IngredientsPanel } from './IngredientsPanel'
import { SpeciesPanel } from './SpeciesPanel'
import { SubstitutesPanel } from './SubstitutesPanel'
import { hasPermission, useCurrentUser } from '../auth'
import { usePublicSettings } from '../settings'
import {
  useCreateProduct,
  useProduct,
  useSetArchived,
  useUpdateProduct,
  type ProductDetail,
  type ProductWrite,
} from './productApi'
import { referenceLabel, useReferenceList, type ReferenceListPath } from './referenceApi'

const DECIMAL_3 = /^\d+(\.\d{1,3})?$/
/** ADR-049: prices per base unit, up to 4 decimals. */
const PRICE = /^\d+(\.\d{1,4})?$/
const schema = z.object({
  sku: z.string().trim().min(1, 'validation.required').max(40, 'validation.tooLong'),
  commercialName: z.string().trim().min(1, 'validation.required').max(200, 'validation.tooLong'),
  categoryId: z.string(),
  dosageFormId: z.string(),
  manufacturerId: z.string(),
  baseUnit: z.string().trim().min(1, 'validation.required').max(20, 'validation.tooLong'),
  divisible: z.boolean(),
  vatRate: z.string().regex(/^\d{1,3}(\.\d{1,2})?$/, 'fieldErrors.RANGE'),
  minStock: z.string().regex(DECIMAL_3, 'fieldErrors.SCALE'),
  maxStock: z.union([z.literal(''), z.string().regex(DECIMAL_3, 'fieldErrors.SCALE')]),
  reorderPoint: z.string().regex(DECIMAL_3, 'fieldErrors.SCALE'),
  purchasePrice: z.string().regex(PRICE, 'fieldErrors.SCALE'),
  salePrice: z.string().regex(PRICE, 'fieldErrors.SCALE'),
})
type Form = z.infer<typeof schema>
const FIELDS = ['sku', 'commercialName', 'categoryId', 'dosageFormId', 'manufacturerId', 'baseUnit', 'vatRate',
  'minStock', 'maxStock', 'reorderPoint', 'purchasePrice', 'salePrice'] as const

function toForm(p?: ProductDetail, defaultVat?: string): Form {
  return {
    sku: p?.sku ?? '',
    commercialName: p?.commercialName ?? '',
    categoryId: p?.category ? String(p.category.id) : '',
    dosageFormId: p?.dosageForm ? String(p.dosageForm.id) : '',
    manufacturerId: p?.manufacturer ? String(p.manufacturer.id) : '',
    baseUnit: p?.baseUnit ?? '',
    divisible: p?.divisible ?? false,
    vatRate: p?.vatRate ?? defaultVat ?? '',
    minStock: p?.minStock ?? '0',
    maxStock: p?.maxStock ?? '',
    reorderPoint: p?.reorderPoint ?? '0',
    purchasePrice: p?.purchasePrice ?? '0',
    salePrice: p?.salePrice ?? '0',
  }
}

/** Prices are sent only by a user holding `price.edit`; omitted, the server keeps them (BR-PROD-006). */
function toWrite(v: Form, withPrices: boolean, version?: number): ProductWrite {
  const id = (value: string) => (value === '' ? null : Number(value))
  return {
    sku: v.sku,
    commercialName: v.commercialName,
    categoryId: id(v.categoryId),
    dosageFormId: id(v.dosageFormId),
    manufacturerId: id(v.manufacturerId),
    baseUnit: v.baseUnit,
    divisible: v.divisible,
    vatRate: v.vatRate,
    minStock: v.minStock,
    maxStock: v.maxStock === '' ? null : v.maxStock,
    reorderPoint: v.reorderPoint,
    ...(withPrices ? { purchasePrice: v.purchasePrice, salePrice: v.salePrice } : {}),
    version,
  }
}

function ReferenceSelect({ path, i18nKey, label, disabled, error, ...register }: {
  path: ReferenceListPath
  i18nKey: string
  label: string
  disabled?: boolean
  error?: string
} & ReturnType<ReturnType<typeof useForm<Form>>['register']>) {
  const { t } = useTranslation()
  const items = useReferenceList(path)
  return (
    <SelectField
      label={label}
      disabled={disabled}
      error={error}
      options={[{ value: '', label: '—' }, ...(items.data ?? []).map((r) => ({ value: String(r.id), label: referenceLabel(r, i18nKey, t) }))]}
      {...register}
    />
  )
}

/** Product form shared by creation and the detail page (frontend.md §4.1). */
function ProductForm({ product, onSaved }: { product?: ProductDetail; onSaved: (p: ProductDetail) => void }) {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const publicSettings = usePublicSettings(true)
  const create = useCreateProduct()
  const update = useUpdateProduct(product?.id ?? 0)
  const editable = product ? hasPermission(current, 'product.update') : hasPermission(current, 'product.create')
  const canEditPrices = editable && hasPermission(current, 'price.edit')
  const [problem, setProblem] = useState<Problem>()
  const [saved, setSaved] = useState(false)
  const { register, handleSubmit, formState, setError } = useForm<Form>({
    resolver: zodResolver(schema),
    values: toForm(product, publicSettings.data?.defaultVatRate),
  })
  const fieldError = (name: keyof Form) => {
    const message = formState.errors[name]?.message
    return message === undefined ? undefined : t(message)
  }
  const mutation = product ? update : create

  const onSubmit = handleSubmit((v) => {
    setProblem(undefined)
    setSaved(false)
    mutation.mutate(toWrite(v, canEditPrices, product?.version), {
      onSuccess: (p) => {
        setSaved(true)
        onSaved(p)
      },
      onError: (error) => setProblem(placeServerErrors(error, FIELDS, setError)),
    })
  })

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4 max-w-2xl">
      {saved && <Alert tone="ok">{t('admin.saved')}</Alert>}
      {problem && problem.code !== 'CONCURRENT_MODIFICATION' && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
      {problem?.code === 'CONCURRENT_MODIFICATION' && <StaleNotice id={product?.id} />}
      <div className="grid grid-cols-2 gap-4">
        <TextField label={t('catalog.products.sku')} disabled={!editable} error={fieldError('sku')} {...register('sku')} />
        <TextField label={t('catalog.products.name')} disabled={!editable} error={fieldError('commercialName')} {...register('commercialName')} />
        <ReferenceSelect path="categories" i18nKey="categories" label={t('catalog.products.category')} disabled={!editable} error={fieldError('categoryId')} {...register('categoryId')} />
        <ReferenceSelect path="dosage-forms" i18nKey="dosageForms" label={t('catalog.products.dosageForm')} disabled={!editable} error={fieldError('dosageFormId')} {...register('dosageFormId')} />
        <ReferenceSelect path="manufacturers" i18nKey="manufacturers" label={t('catalog.products.manufacturer')} disabled={!editable} error={fieldError('manufacturerId')} {...register('manufacturerId')} />
        <TextField label={t('catalog.products.baseUnit')} disabled={!editable} error={fieldError('baseUnit')} {...register('baseUnit')} />
        <label className="flex items-center gap-2 text-sm col-span-2">
          <input type="checkbox" disabled={!editable} {...register('divisible')} />
          {t('catalog.products.divisible')}
        </label>
        <TextField label={t('catalog.products.vatRate')} inputMode="decimal" disabled={!editable} error={fieldError('vatRate')} {...register('vatRate')} />
        <TextField label={t('catalog.products.minStock')} inputMode="decimal" disabled={!editable} error={fieldError('minStock')} {...register('minStock')} />
        <TextField label={t('catalog.products.maxStock')} inputMode="decimal" disabled={!editable} error={fieldError('maxStock')} {...register('maxStock')} />
        <TextField label={t('catalog.products.reorderPoint')} inputMode="decimal" disabled={!editable} error={fieldError('reorderPoint')} {...register('reorderPoint')} />
        <TextField label={t('catalog.products.purchasePrice')} inputMode="decimal" disabled={!canEditPrices} error={fieldError('purchasePrice')} {...register('purchasePrice')} />
        <TextField label={t('catalog.products.salePrice')} inputMode="decimal" disabled={!canEditPrices} error={fieldError('salePrice')} {...register('salePrice')} />
      </div>
      {editable && !canEditPrices && <p className="text-sm text-mute">{t('catalog.products.pricesReadOnly')}</p>}
      {product && (
        <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
          <dt className="text-mute">{t('catalog.products.updatedAt')}</dt>
          <dd>{formatDateTime(product.updatedAt)}</dd>
        </dl>
      )}
      {editable && (
        <Button type="submit" disabled={mutation.isPending}>
          {t('admin.save')}
        </Button>
      )}
    </form>
  )
}

/** `CONCURRENT_MODIFICATION` (ADR-027): offer to reload the latest version (frontend.md §4.1). */
function StaleNotice({ id }: { id?: number }) {
  const { t } = useTranslation()
  const product = useProduct(id ?? 0)
  return (
    <Alert tone="danger">
      {t('admin.stale')}{' '}
      <button type="button" className="underline font-medium" onClick={() => void product.refetch()}>
        {t('catalog.products.reload')}
      </button>
    </Alert>
  )
}

/** `/products/new` (`product.create`). */
export function NewProductPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{t('catalog.products.new')}</h1>
      <ProductForm onSaved={(p) => void navigate(`/products/${p.id}`, { replace: true })} />
    </section>
  )
}

const TABS = ['general', 'ingredients', 'species', 'substitutes'] as const

/** The detail tabs of frontend.md §4.1; each slice adds its own. */
function ProductTabs({ product }: { product: ProductDetail }) {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const [tab, setTab] = useState<(typeof TABS)[number]>('general')
  return (
    <>
      <div role="tablist" aria-label={product.commercialName} className="flex gap-1 border-b border-line">
        {TABS.map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={tab === name}
            onClick={() => setTab(name)}
            className={`px-3 py-2 text-sm font-medium border-b-2 ${tab === name ? 'border-brand text-brand' : 'border-transparent text-ink'}`}
          >
            {t(`catalog.products.tabs.${name}`)}
          </button>
        ))}
      </div>
      <div role="tabpanel">
        {tab === 'general' && <ProductForm key={product.id} product={product} onSaved={() => undefined} />}
        {tab === 'ingredients' && (
          <IngredientsPanel key={product.version} product={product} editable={hasPermission(current, 'product.update')} />
        )}
        {tab === 'species' && (
          <SpeciesPanel key={product.version} product={product} editable={hasPermission(current, 'product.update')} />
        )}
        {tab === 'substitutes' && <SubstitutesPanel productId={product.id} />}
      </div>
    </>
  )
}

/** `/products/:id` (`product.read`; editing `product.update`; archive `product.archive`). */
export function ProductDetailPage() {
  const { t } = useTranslation()
  const id = Number(useParams().id)
  const { data: current } = useCurrentUser()
  const product = useProduct(id)
  const setArchived = useSetArchived(id)

  if (product.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (product.error instanceof ApiError) {
    return <Alert tone="danger">{problemMessage(product.error.problem, t)}</Alert>
  }
  const p = product.data
  if (!p) {
    return null
  }
  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{p.commercialName}</h1>
        {p.archived && <span className="text-sm text-mute">{t('catalog.products.archived')}</span>}
        {hasPermission(current, 'product.archive') && (
          <Button variant="secondary" className="ms-auto" disabled={setArchived.isPending} onClick={() => setArchived.mutate(!p.archived)}>
            {p.archived ? t('catalog.products.restore') : t('catalog.products.archive')}
          </Button>
        )}
      </div>
      {setArchived.error instanceof ApiError && <Alert tone="danger">{problemMessage(setArchived.error.problem, t)}</Alert>}
      <ProductTabs product={p} />
    </section>
  )
}
