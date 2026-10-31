import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { hasPermission, useCurrentUser } from '../auth'
import { CreditLimitDialog, CreditLimitLabel } from './CreditLimitDialog'
import { CustomerBalance, CustomerSalesPanel } from '../sales'
import { CustomerPricesPanel } from './CustomerPricesPanel'
import {
  CUSTOMER_TYPES,
  useCustomer,
  useCustomerCategories,
  useCustomers,
  useSaveCustomer,
  useSetCustomerActive,
  useSimilarCustomers,
  type CustomerDetail,
  type CustomerType,
  type SimilarCustomer,
} from './customerApi'

type Field = 'name' | 'customerType' | 'categoryId' | 'farmingType' | 'wilaya' | 'commune' | 'address' | 'phone' | 'email' | 'taxId' | 'paymentTermsDays' | 'veterinarianId'
const FIELDS: readonly Field[] = ['name', 'customerType', 'categoryId', 'farmingType', 'wilaya', 'commune', 'address', 'phone', 'email', 'taxId', 'paymentTermsDays', 'veterinarianId']

/** Field codes whose generic message would mislead on a customer form (api.md §3 field codes). */
const CUSTOMER_FIELD_CODES: Partial<Record<string, string>> = {
  'phone:FORMAT': 'customers.errors.phoneFormat',
  'veterinarianId:SELF': 'customers.errors.self',
  'veterinarianId:NOT_VETERINARIAN': 'customers.errors.notVeterinarian',
}

/** `/customers/new` (frontend.md §4.6): a new customer starts at "cash only" (ADR-109). */
export function NewCustomerPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  return (
    <section className="flex flex-col gap-4 max-w-3xl">
      <h1 className="text-2xl font-semibold">{t('customers.new')}</h1>
      <p className="text-sm text-mute">{t('customers.startsCashOnly')}</p>
      <CustomerForm onSaved={(saved) => navigate(`/customers/${saved.id}`, { replace: true })} />
    </section>
  )
}

type Tab = 'overview' | 'prices' | 'history'

/**
 * `/customers/:id` (frontend.md §4.6): Overview (record, balance, credit limit, deactivate), Prices and History tabs. Each control
 * appears only with its permission; the server refuses anyway (BR-SEC-002).
 */
export function CustomerPage() {
  const { t } = useTranslation()
  const id = Number(useParams().id)
  const { data: current } = useCurrentUser()
  const customer = useCustomer(id)
  const setActive = useSetCustomerActive(id)
  const [tab, setTab] = useState<Tab>('overview')
  const [creditOpen, setCreditOpen] = useState(false)
  const manage = hasPermission(current, 'customer.manage')
  const manageCredit = hasPermission(current, 'customer.credit.manage')
  const managePrices = hasPermission(current, 'customer.price.manage')

  if (customer.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (customer.error instanceof ApiError || !customer.data) {
    return <Alert tone="danger">{customer.error instanceof ApiError ? problemMessage(customer.error.problem, t) : t('errors.UNKNOWN')}</Alert>
  }
  const data = customer.data
  return (
    <section className="flex flex-col gap-6 max-w-4xl">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{data.name}</h1>
        <span className="text-mute">{t(data.active ? 'customers.active' : 'customers.inactive')}</span>
        {manage && (
          <Button variant="secondary" className="ms-auto" disabled={setActive.isPending} onClick={() => setActive.mutate(!data.active)}>
            {t(data.active ? 'customers.deactivate' : 'customers.activate')}
          </Button>
        )}
      </div>
      {setActive.error instanceof ApiError && <Alert tone="danger">{problemMessage(setActive.error.problem, t)}</Alert>}
      <div role="tablist" aria-label={data.name} className="flex gap-2 border-b border-line">
        {(hasPermission(current, 'sale.read') ? (['overview', 'prices', 'history'] as const) : (['overview', 'prices'] as const)).map((k) => (
          <button
            key={k}
            type="button"
            role="tab"
            aria-selected={tab === k}
            onClick={() => setTab(k)}
            className={`px-3 py-2 text-sm -mb-px border-b-2 ${tab === k ? 'border-brand text-ink font-medium' : 'border-transparent text-mute'}`}
          >
            {t(`customers.tabs.${k}`)}
          </button>
        ))}
      </div>
      {tab === 'overview' ? (
        <div role="tabpanel" className="flex flex-col gap-6">
          <div className="flex items-center gap-4 rounded-ctl border border-line px-4 py-3">
            <span className="text-sm text-mute">{t('customers.credit.label')}</span>
            <strong data-testid="credit-limit">
              <CreditLimitLabel limit={data.creditLimit} />
            </strong>
            {manageCredit && (
              <Button variant="secondary" className="ms-auto" onClick={() => setCreditOpen(true)}>
                {t('customers.credit.change')}
              </Button>
            )}
          </div>
          <CustomerBalance customerId={id} />
          {creditOpen && <CreditLimitDialog customer={data} onClose={() => setCreditOpen(false)} />}
          {manage ? <CustomerForm key={data.version} customer={data} onSaved={() => undefined} /> : <CustomerFacts customer={data} />}
        </div>
      ) : tab === 'history' ? (
        <div role="tabpanel">
          <CustomerSalesPanel customerId={id} />
        </div>
      ) : (
        <div role="tabpanel">
          <CustomerPricesPanel customerId={id} manage={managePrices} />
        </div>
      )}
    </section>
  )
}

function CustomerFacts({ customer }: { customer: CustomerDetail }) {
  const { t } = useTranslation()
  const rows: [string, string][] = [
    [t('customers.type'), t(`customers.types.${customer.customerType}`)],
    [t('customers.category'), customer.category ? `${customer.category.name} (${customer.category.discountPct} %)` : '—'],
    [t('customers.farmingType'), customer.farmingType ?? '—'],
    [t('customers.place'), [customer.commune, customer.wilaya].filter(Boolean).join(', ') || '—'],
    [t('customers.phone'), customer.phone ?? '—'],
    [t('customers.taxId'), customer.taxId ?? '—'],
    [t('customers.paymentTermsDays'), String(customer.paymentTermsDays)],
    [t('customers.veterinarian'), customer.veterinarian?.name ?? '—'],
  ]
  return (
    <dl className="grid grid-cols-2 gap-2 text-sm">
      {rows.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-mute">{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  )
}

function CustomerForm({ customer, onSaved }: { customer?: CustomerDetail; onSaved: (saved: CustomerDetail) => void }) {
  const { t } = useTranslation()
  const save = useSaveCustomer()
  const similar = useSimilarCustomers()
  const categories = useCustomerCategories()
  const veterinarians = useCustomers({ q: '', type: 'veterinarian', active: 'true', page: 0 }, 100)
  const [values, setValues] = useState<Record<Field, string>>({
    name: customer?.name ?? '',
    customerType: customer?.customerType ?? 'breeder',
    categoryId: customer?.category ? String(customer.category.id) : '',
    farmingType: customer?.farmingType ?? '',
    wilaya: customer?.wilaya ?? '',
    commune: customer?.commune ?? '',
    address: customer?.address ?? '',
    phone: customer?.phone ?? '',
    email: customer?.email ?? '',
    taxId: customer?.taxId ?? '',
    paymentTermsDays: String(customer?.paymentTermsDays ?? 0),
    veterinarianId: customer?.veterinarian ? String(customer.veterinarian.id) : '',
  })
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({})
  const [problem, setProblem] = useState<Problem>()
  const [saved, setSaved] = useState(false)
  const [warnings, setWarnings] = useState<SimilarCustomer[]>()
  const set = (field: Field) => (value: string) => {
    setValues((v) => ({ ...v, [field]: value }))
    if (field === 'name' || field === 'phone' || field === 'taxId') {
      setWarnings(undefined)
    }
  }
  const blank = (value: string) => (value.trim() === '' ? null : value.trim())

  const persist = () => {
    save.mutate(
      {
        id: customer?.id,
        version: customer?.version,
        customer: {
          name: values.name.trim(),
          customerType: values.customerType as CustomerType,
          categoryId: values.categoryId === '' ? null : Number(values.categoryId),
          farmingType: blank(values.farmingType),
          wilaya: blank(values.wilaya),
          commune: blank(values.commune),
          address: blank(values.address),
          phone: blank(values.phone),
          email: blank(values.email),
          taxId: blank(values.taxId),
          paymentTermsDays: values.paymentTermsDays.trim() === '' ? null : Number(values.paymentTermsDays),
          veterinarianId: values.veterinarianId === '' ? null : Number(values.veterinarianId),
        },
      },
      {
        onSuccess: (result) => {
          setSaved(true)
          setWarnings(undefined)
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
              server[field] = t(CUSTOMER_FIELD_CODES[`${field}:${e.code}`] ?? `fieldErrors.${e.code}`)
            }
          }
          if (error.problem.code === 'DUPLICATE_RESOURCE') {
            const field = FIELDS.find((f) => f === error.problem.context.field) ?? 'name'
            server[field] = t('customers.errors.duplicate')
          }
          setErrors(server)
          if (Object.keys(server).length === 0) {
            setProblem(error.problem)
          }
        },
      },
    )
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setSaved(false)
    setProblem(undefined)
    if (values.name.trim() === '') {
      setErrors({ name: t('fieldErrors.REQUIRED') })
      return
    }
    setErrors({})
    if (warnings !== undefined) {
      persist()
      return
    }
    similar.mutate(
      { name: blank(values.name), phone: blank(values.phone), taxId: blank(values.taxId), excludeId: customer?.id ?? null },
      {
        onSuccess: (found) => {
          if (found.length === 0) {
            persist()
          } else {
            setWarnings(found)
          }
        },
        onError: (error) => {
          if (error instanceof ApiError) {
            setProblem(error.problem)
          }
        },
      },
    )
  }

  return (
    <form onSubmit={submit} noValidate className="grid grid-cols-1 sm:grid-cols-2 gap-4">
      {saved && customer && (
        <div className="sm:col-span-2">
          <Alert tone="ok">{t('customers.saved')}</Alert>
        </div>
      )}
      {problem && (
        <div className="sm:col-span-2">
          <Alert tone="danger">{problemMessage(problem, t)}</Alert>
        </div>
      )}
      <TextField label={t('customers.name')} value={values.name} error={errors.name} onChange={(e) => set('name')(e.target.value)} />
      <SelectField
        label={t('customers.type')}
        value={values.customerType}
        error={errors.customerType}
        onChange={(e) => set('customerType')(e.target.value)}
        options={CUSTOMER_TYPES.map((k) => ({ value: k, label: t(`customers.types.${k}`) }))}
      />
      <SelectField
        label={t('customers.category')}
        value={values.categoryId}
        error={errors.categoryId}
        onChange={(e) => set('categoryId')(e.target.value)}
        options={[{ value: '', label: t('customers.noCategory') }, ...(categories.data ?? []).map((c) => ({ value: String(c.id), label: `${c.name} (${c.discountPct} %)` }))]}
      />
      <TextField label={t('customers.farmingType')} value={values.farmingType} error={errors.farmingType} onChange={(e) => set('farmingType')(e.target.value)} />
      <TextField label={t('customers.wilaya')} value={values.wilaya} error={errors.wilaya} onChange={(e) => set('wilaya')(e.target.value)} />
      <TextField label={t('customers.commune')} value={values.commune} error={errors.commune} onChange={(e) => set('commune')(e.target.value)} />
      <TextField label={t('customers.phone')} type="tel" dir="ltr" value={values.phone} error={errors.phone} onChange={(e) => set('phone')(e.target.value)} />
      <TextField label={t('customers.email')} type="email" dir="ltr" value={values.email} error={errors.email} onChange={(e) => set('email')(e.target.value)} />
      <TextField label={t('customers.taxId')} dir="ltr" value={values.taxId} error={errors.taxId} onChange={(e) => set('taxId')(e.target.value)} />
      <TextField
        label={t('customers.paymentTermsDays')}
        inputMode="numeric"
        value={values.paymentTermsDays}
        error={errors.paymentTermsDays}
        onChange={(e) => set('paymentTermsDays')(e.target.value)}
      />
      <SelectField
        label={t('customers.veterinarian')}
        value={values.veterinarianId}
        error={errors.veterinarianId}
        onChange={(e) => set('veterinarianId')(e.target.value)}
        options={[
          { value: '', label: t('customers.noVeterinarian') },
          ...(veterinarians.data?.content ?? []).filter((v) => v.id !== customer?.id).map((v) => ({ value: String(v.id), label: v.name })),
          ...(customer?.veterinarian && !(veterinarians.data?.content ?? []).some((v) => v.id === customer.veterinarian?.id)
            ? [{ value: String(customer.veterinarian.id), label: customer.veterinarian.name }]
            : []),
        ]}
      />
      <div className="sm:col-span-2">
        <TextField label={t('customers.address')} value={values.address} error={errors.address} onChange={(e) => set('address')(e.target.value)} />
      </div>
      {warnings !== undefined && (
        <div className="sm:col-span-2 flex flex-col gap-2 rounded-ctl border border-warn/40 bg-warn/10 px-3 py-2 text-sm" role="alert">
          <p className="font-medium">{t('customers.similar.title')}</p>
          <ul className="list-disc ps-5">
            {warnings.map((w) => (
              <li key={`${w.customerId}-${w.field}`}>
                {t(`customers.similar.${w.field}`)}{' '}
                <Link to={`/customers/${w.customerId}`} target="_blank" rel="noopener" className="text-brand underline underline-offset-4">
                  {t('customers.similar.open', { id: w.customerId })}
                </Link>
              </li>
            ))}
          </ul>
          <p>{t('customers.similar.confirm')}</p>
        </div>
      )}
      <div className="sm:col-span-2">
        <Button type="submit" disabled={save.isPending || similar.isPending}>
          {t(warnings !== undefined ? 'customers.similar.saveAnyway' : 'customers.save')}
        </Button>
      </div>
    </form>
  )
}
