import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatDateTime, formatQuantity } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { useLots, type LotSummary } from '../inventory/lotApi'
import { useCreateReturn, useSupplierReturn, useSupplierReturns } from './returnApi'
import { useSuppliers } from './supplierApi'

/** `/supplier-returns` (frontend.md §4.5, W4): returns newest first, by supplier. */
export function SupplierReturnsPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const [params, setParams] = useSearchParams()
  const supplierId = params.get('supplierId') ?? ''
  const page = Number(params.get('page') ?? '0')
  const returns = useSupplierReturns(supplierId, page)
  const suppliers = useSuppliers('', '', 0, 100)
  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{t('purchasing.returns.title')}</h1>
        {hasPermission(current, 'return.supplier.create') && (
          <Link to="/supplier-returns/new" className="ms-auto h-10 px-4 inline-flex items-center rounded-ctl bg-brand text-brand-ink text-sm font-medium">
            {t('purchasing.returns.new')}
          </Link>
        )}
      </div>
      <div className="w-64">
        <SelectField
          label={t('purchasing.orders.supplier')}
          value={supplierId}
          onChange={(e) => setParams(e.target.value === '' ? {} : { supplierId: e.target.value }, { replace: true })}
          options={[{ value: '', label: t('admin.all') }, ...(suppliers.data?.content ?? []).map((s) => ({ value: String(s.id), label: s.name }))]}
        />
      </div>
      {returns.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : returns.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(returns.error.problem, t)}</Alert>
      ) : !returns.data || returns.data.content.length === 0 ? (
        <p className="text-mute">{t('purchasing.returns.empty')}</p>
      ) : (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('purchasing.orders.number')}</th>
                <th className="text-start">{t('purchasing.orders.supplier')}</th>
                <th className="text-start">{t('purchasing.reason')}</th>
                <th className="text-start">{t('purchasing.returns.createdAt')}</th>
              </tr>
            </thead>
            <tbody>
              {returns.data.content.map((r) => (
                <tr key={r.id} className="border-b border-line">
                  <td className="py-2">
                    <Link to={`/supplier-returns/${r.id}`} className="text-brand underline underline-offset-4">
                      {r.number}
                    </Link>
                  </td>
                  <td>{r.supplier.name}</td>
                  <td>{r.reason}</td>
                  <td>{formatDateTime(r.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={returns.data.page} onPage={(next) => setParams({ ...(supplierId === '' ? {} : { supplierId }), page: String(next) }, { replace: true })} />
        </>
      )}
    </section>
  )
}

interface ReturnLineState {
  lot: LotSummary
  quantity: string
}

/**
 * `/supplier-returns/new` (W4, ADR-101): a supplier (an inactive one too), lots found by their number with stock, in
 * any status, quantities in base units and a reason; a lot received from another supplier is refused by the
 * server on its line (`OTHER_SUPPLIER`), more than the balance with `STOCK_INSUFFICIENT`.
 */
export function NewSupplierReturnPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const create = useCreateReturn()
  const suppliers = useSuppliers('', '', 0, 100)
  const [supplierId, setSupplierId] = useState('')
  const [reason, setReason] = useState('')
  const [q, setQ] = useState('')
  const [lines, setLines] = useState<ReturnLineState[]>([])
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [problem, setProblem] = useState<Problem>()
  const lots = useLots({ productId: '', status: '', alertLevel: '', expiringWithinDays: '', hasStock: true, q, page: 0 })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const local: Record<string, string> = {}
    if (supplierId === '') {
      local.supplierId = t('fieldErrors.REQUIRED')
    }
    if (reason.trim() === '') {
      local.reason = t('fieldErrors.REQUIRED')
    }
    setErrors(local)
    setProblem(undefined)
    if (Object.keys(local).length > 0) {
      return
    }
    create.mutate(
      { supplierId: Number(supplierId), reason: reason.trim(), lines: lines.map((l) => ({ lotId: l.lot.id, quantity: l.quantity.trim().replace(',', '.'), unitId: null })) },
      {
        onSuccess: (created) => navigate(`/supplier-returns/${created.id}`),
        onError: (error) => {
          if (!(error instanceof ApiError)) {
            return
          }
          const placed = Object.fromEntries(error.problem.errors.map((e) => [e.field, t(`fieldErrors.${e.code}`)]))
          setErrors(placed)
          if (Object.keys(placed).length === 0) {
            setProblem(error.problem)
          }
        },
      },
    )
  }

  return (
    <section className="flex flex-col gap-4 max-w-4xl">
      <h1 className="text-2xl font-semibold">{t('purchasing.returns.new')}</h1>
      <form onSubmit={submit} noValidate className="flex flex-col gap-4">
        {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
        {errors.lines && <Alert tone="danger">{errors.lines}</Alert>}
        <div className="flex flex-wrap gap-4">
          <div className="w-72">
            <SelectField
              label={t('purchasing.orders.supplier')}
              value={supplierId}
              error={errors.supplierId}
              onChange={(e) => setSupplierId(e.target.value)}
              options={[{ value: '', label: '—' }, ...(suppliers.data?.content ?? []).map((s) => ({ value: String(s.id), label: s.name }))]}
            />
          </div>
          <div className="w-96">
            <TextField label={t('purchasing.reason')} value={reason} error={errors.reason} onChange={(e) => setReason(e.target.value)} />
          </div>
        </div>
        {lines.map((l, i) => (
          <div key={l.lot.id} className="flex flex-wrap items-end gap-3 rounded-ctl border border-line p-3 text-sm">
            <span className="w-full font-medium">
              {t('purchasing.returns.lotLine', { name: l.lot.product.commercialName, lotNumber: l.lot.lotNumber, quantity: `${formatQuantity(l.lot.quantityOnHand)} ${l.lot.product.baseUnit}` })}
            </span>
            {errors[`lines[${i}].lotId`] && <p className="w-full text-danger">{errors[`lines[${i}].lotId`]}</p>}
            <div className="w-40">
              <TextField
                label={t('purchasing.returns.quantity', { unit: l.lot.product.baseUnit })}
                inputMode="decimal"
                value={l.quantity}
                error={errors[`lines[${i}].quantity`]}
                onChange={(e) => setLines((ls) => ls.map((x) => (x.lot.id === l.lot.id ? { ...x, quantity: e.target.value } : x)))}
              />
            </div>
            <Button type="button" variant="secondary" onClick={() => setLines((ls) => ls.filter((x) => x.lot.id !== l.lot.id))}>
              {t('purchasing.orders.remove')}
            </Button>
          </div>
        ))}
        <div className="flex flex-col gap-2 max-w-xl">
          <TextField label={t('purchasing.returns.findLot')} type="search" value={q} onChange={(e) => setQ(e.target.value)} />
          {q.trim() !== '' && (
            <ul className="flex flex-col gap-2">
              {(lots.data?.content ?? [])
                .filter((lot) => !lines.some((l) => l.lot.id === lot.id))
                .map((lot) => (
                  <li key={lot.id} className="flex items-center gap-3 rounded-ctl border border-line px-3 py-2 text-sm">
                    <span>{`${lot.lotNumber} — ${lot.product.commercialName} — ${formatDate(lot.expiryDate)}`}</span>
                    <Button type="button" variant="secondary" className="ms-auto" onClick={() => setLines((ls) => [...ls, { lot, quantity: '' }])} aria-label={t('purchasing.returns.pickLot', { lotNumber: lot.lotNumber })}>
                      {t('stock.adjustment.pick')}
                    </Button>
                  </li>
                ))}
            </ul>
          )}
        </div>
        <div>
          <Button type="submit" disabled={create.isPending || lines.length === 0}>
            {t('purchasing.returns.confirm')}
          </Button>
        </div>
      </form>
    </section>
  )
}

/** `/supplier-returns/:id`: the returned lots and their movements. */
export function SupplierReturnPage() {
  const { t } = useTranslation()
  const supplierReturn = useSupplierReturn(Number(useParams().id))
  if (supplierReturn.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (supplierReturn.error instanceof ApiError || !supplierReturn.data) {
    return <Alert tone="danger">{supplierReturn.error instanceof ApiError ? problemMessage(supplierReturn.error.problem, t) : t('errors.UNKNOWN')}</Alert>
  }
  const r = supplierReturn.data
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{r.number}</h1>
      <p className="text-sm">{t('purchasing.returns.summary', { supplier: r.supplier.name, by: r.createdBy.fullName, at: formatDateTime(r.createdAt), reason: r.reason })}</p>
      <table className="w-full text-sm border-collapse">
        <thead>
          <tr className="border-b border-line">
            <th className="text-start py-2">{t('catalog.products.name')}</th>
            <th className="text-start">{t('purchasing.receipts.lotNumber')}</th>
            <th className="text-start">{t('purchasing.receipts.expiry')}</th>
            <th className="text-end">{t('purchasing.orders.quantity')}</th>
          </tr>
        </thead>
        <tbody>
          {r.lines.map((l) => (
            <tr key={l.id} className="border-b border-line">
              <td className="py-2">{l.product.commercialName}</td>
              <td>
                <Link to={`/lots/${l.lotId}`} className="underline">
                  {l.lotNumber}
                </Link>
              </td>
              <td>{formatDate(l.expiryDate)}</td>
              <td className="text-end">{`${formatQuantity(l.quantityBase)} ${l.product.baseUnit}`}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
