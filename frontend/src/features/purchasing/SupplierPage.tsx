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
import { hasPermission, useCurrentUser } from '../auth'
import { PriceHistoryPanel } from './PriceHistoryPanel'
import { SUPPLIER_KINDS, useSaveSupplier, useSetSupplierActive, useSupplier, type SupplierDetail, type SupplierKind } from './supplierApi'

type Field = 'name' | 'kind' | 'taxId' | 'phone' | 'email' | 'address' | 'paymentTermsDays'
const FIELDS: readonly Field[] = ['name', 'kind', 'taxId', 'phone', 'email', 'address', 'paymentTermsDays']

/** `/suppliers/new` (frontend.md §4.5): a new supplier; a duplicate name is shown on the name field (ADR-100). */
export function NewSupplierPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  return (
    <section className="flex flex-col gap-4 max-w-2xl">
      <h1 className="text-2xl font-semibold">{t('purchasing.suppliers.new')}</h1>
      <SupplierForm onSaved={(saved) => navigate(`/suppliers/${saved.id}`, { replace: true })} />
    </section>
  )
}

/**
 * `/suppliers/:id` (frontend.md §4.5): edit with the current version, deactivate / activate (an inactive supplier
 * cannot be ordered from or received, BR-PURCH-011), and the price history with manual entries (ADR-100).
 */
export function SupplierPage() {
  const { t } = useTranslation()
  const id = Number(useParams().id)
  const { data: current } = useCurrentUser()
  const supplier = useSupplier(id)
  const setActive = useSetSupplierActive(id)
  const manage = hasPermission(current, 'supplier.manage')

  if (supplier.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (supplier.error instanceof ApiError || !supplier.data) {
    return <Alert tone="danger">{supplier.error instanceof ApiError ? problemMessage(supplier.error.problem, t) : t('errors.UNKNOWN')}</Alert>
  }
  const data = supplier.data
  return (
    <section className="flex flex-col gap-6 max-w-4xl">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{data.name}</h1>
        <span className="text-mute">{t(data.active ? 'purchasing.suppliers.active' : 'purchasing.suppliers.inactive')}</span>
        {manage && (
          <Button variant="secondary" className="ms-auto" disabled={setActive.isPending} onClick={() => setActive.mutate(!data.active)}>
            {t(data.active ? 'purchasing.suppliers.deactivate' : 'purchasing.suppliers.activate')}
          </Button>
        )}
      </div>
      {setActive.error instanceof ApiError && <Alert tone="danger">{problemMessage(setActive.error.problem, t)}</Alert>}
      {manage ? <SupplierForm key={data.version} supplier={data} onSaved={() => undefined} /> : <SupplierFacts supplier={data} />}
      <PriceHistoryPanel supplierId={id} manage={manage} />
    </section>
  )
}

function SupplierFacts({ supplier }: { supplier: SupplierDetail }) {
  const { t } = useTranslation()
  return (
    <dl className="grid grid-cols-2 gap-2 text-sm">
      <dt className="text-mute">{t('purchasing.suppliers.kind')}</dt>
      <dd>{t(`purchasing.suppliers.kinds.${supplier.kind}`)}</dd>
      <dt className="text-mute">{t('purchasing.suppliers.paymentTermsDays')}</dt>
      <dd>{supplier.paymentTermsDays}</dd>
      <dt className="text-mute">{t('purchasing.suppliers.phone')}</dt>
      <dd>{supplier.phone ?? '—'}</dd>
    </dl>
  )
}

function SupplierForm({ supplier, onSaved }: { supplier?: SupplierDetail; onSaved: (saved: SupplierDetail) => void }) {
  const { t } = useTranslation()
  const save = useSaveSupplier()
  const [values, setValues] = useState<Record<Field, string>>({
    name: supplier?.name ?? '',
    kind: supplier?.kind ?? 'wholesaler',
    taxId: supplier?.taxId ?? '',
    phone: supplier?.phone ?? '',
    email: supplier?.email ?? '',
    address: supplier?.address ?? '',
    paymentTermsDays: String(supplier?.paymentTermsDays ?? 0),
  })
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({})
  const [problem, setProblem] = useState<Problem>()
  const [saved, setSaved] = useState(false)
  const set = (field: Field) => (value: string) => setValues((v) => ({ ...v, [field]: value }))
  const blank = (value: string) => (value.trim() === '' ? null : value.trim())

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setSaved(false)
    setProblem(undefined)
    if (values.name.trim() === '') {
      setErrors({ name: t('fieldErrors.REQUIRED') })
      return
    }
    setErrors({})
    save.mutate(
      {
        id: supplier?.id,
        version: supplier?.version,
        supplier: {
          name: values.name.trim(),
          kind: values.kind as SupplierKind,
          taxId: blank(values.taxId),
          phone: blank(values.phone),
          email: blank(values.email),
          address: blank(values.address),
          paymentTermsDays: values.paymentTermsDays.trim() === '' ? null : Number(values.paymentTermsDays),
        },
      },
      {
        onSuccess: (result) => {
          setSaved(true)
          onSaved(result)
        },
        onError: (error) => {
          if (!(error instanceof ApiError)) {
            return
          }
          const server: Partial<Record<Field, string>> = {}
          for (const e of error.problem.errors) {
            const field = FIELDS.find((f) => f === e.field)
            if (field !== undefined) {
              server[field] = t(`fieldErrors.${e.code}`)
            }
          }
          if (error.problem.code === 'DUPLICATE_RESOURCE') {
            server.name = t('fieldErrors.DUPLICATE')
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
    <form onSubmit={submit} noValidate className="grid grid-cols-1 sm:grid-cols-2 gap-4">
      {saved && supplier && (
        <div className="sm:col-span-2">
          <Alert tone="ok">{t('purchasing.suppliers.saved')}</Alert>
        </div>
      )}
      {problem && (
        <div className="sm:col-span-2">
          <Alert tone="danger">{problemMessage(problem, t)}</Alert>
        </div>
      )}
      <TextField label={t('purchasing.suppliers.name')} value={values.name} error={errors.name} onChange={(e) => set('name')(e.target.value)} />
      <SelectField
        label={t('purchasing.suppliers.kind')}
        value={values.kind}
        error={errors.kind}
        onChange={(e) => set('kind')(e.target.value)}
        options={SUPPLIER_KINDS.map((k) => ({ value: k, label: t(`purchasing.suppliers.kinds.${k}`) }))}
      />
      <TextField label={t('purchasing.suppliers.taxId')} value={values.taxId} error={errors.taxId} onChange={(e) => set('taxId')(e.target.value)} />
      <TextField
        label={t('purchasing.suppliers.paymentTermsDays')}
        inputMode="numeric"
        value={values.paymentTermsDays}
        error={errors.paymentTermsDays}
        onChange={(e) => set('paymentTermsDays')(e.target.value)}
      />
      <TextField label={t('purchasing.suppliers.phone')} value={values.phone} error={errors.phone} onChange={(e) => set('phone')(e.target.value)} />
      <TextField label={t('purchasing.suppliers.email')} type="email" value={values.email} error={errors.email} onChange={(e) => set('email')(e.target.value)} />
      <div className="sm:col-span-2">
        <TextField label={t('purchasing.suppliers.address')} value={values.address} error={errors.address} onChange={(e) => set('address')(e.target.value)} />
      </div>
      <div className="sm:col-span-2">
        <Button type="submit" disabled={save.isPending}>
          {t('purchasing.save')}
        </Button>
      </div>
    </form>
  )
}
