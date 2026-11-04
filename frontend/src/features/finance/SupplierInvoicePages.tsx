import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatDateTime, formatMoney } from '../../lib/format'
import { ReasonDialog } from '../purchasing/OrderDetailPage'
import { useReceipts } from '../purchasing/receiptApi'
import { useSupplierReturns } from '../purchasing/returnApi'
import { useSuppliers } from '../purchasing/supplierApi'
import { AllocationRows } from './AllocationFields'
import { allocationsOf, fieldErrors } from './allocations'
import {
  useApplyCreditNote,
  useCancelSupplierInvoice,
  usePayables,
  useRecordSupplierInvoice,
  useSupplierInvoice,
  useSupplierInvoices,
  type InvoiceWarning,
  type SupplierInvoiceDetail,
  type SupplierInvoiceWrite,
} from './supplierApi'

/**
 * `/supplier-invoices` (frontend.md §4.5, ADR-133): invoices and credit notes by supplier, kind, open and due date;
 * the open amount is the server's.
 */
export function SupplierInvoicesPage() {
  const { t } = useTranslation()
  const [params, setParams] = useSearchParams()
  const filters = {
    supplierId: params.get('supplierId') ?? '',
    creditNote: params.get('creditNote') ?? '',
    open: params.get('open') === 'true',
    dueBefore: params.get('dueBefore') ?? '',
  }
  const page = Number(params.get('page') ?? '0')
  const invoices = useSupplierInvoices(filters, page)
  const suppliers = useSuppliers('', '', 0, 100)
  const update = (next: Record<string, string>) =>
    setParams(
      Object.fromEntries(
        Object.entries({ supplierId: filters.supplierId, creditNote: filters.creditNote, open: filters.open ? 'true' : '', dueBefore: filters.dueBefore, ...next }).filter(
          ([, v]) => v !== '',
        ),
      ),
      { replace: true },
    )

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{t('supplierInvoices.title')}</h1>
        <Link to="/supplier-invoices/new" className="ms-auto h-10 px-4 inline-flex items-center rounded-ctl bg-brand text-brand-ink text-sm font-medium">
          {t('supplierInvoices.new')}
        </Link>
      </div>
      <div className="flex flex-wrap items-end gap-4">
        <div className="w-64">
          <SelectField
            label={t('purchasing.orders.supplier')}
            value={filters.supplierId}
            onChange={(e) => update({ supplierId: e.target.value, page: '' })}
            options={[{ value: '', label: t('admin.all') }, ...(suppliers.data?.content ?? []).map((s) => ({ value: String(s.id), label: s.name }))]}
          />
        </div>
        <div className="w-48">
          <SelectField
            label={t('supplierInvoices.kind')}
            value={filters.creditNote}
            onChange={(e) => update({ creditNote: e.target.value, page: '' })}
            options={[
              { value: '', label: t('admin.all') },
              { value: 'false', label: t('supplierInvoices.kinds.invoice') },
              { value: 'true', label: t('supplierInvoices.kinds.creditNote') },
            ]}
          />
        </div>
        <div className="w-48">
          <TextField label={t('supplierInvoices.dueBefore')} type="date" value={filters.dueBefore} onChange={(e) => update({ dueBefore: e.target.value, page: '' })} />
        </div>
        <label className="flex items-center gap-2 text-sm h-10">
          <input type="checkbox" checked={filters.open} onChange={(e) => update({ open: e.target.checked ? 'true' : '', page: '' })} />
          {t('supplierInvoices.openOnly')}
        </label>
      </div>
      {invoices.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : invoices.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(invoices.error.problem, t)}</Alert>
      ) : !invoices.data || invoices.data.content.length === 0 ? (
        <p className="text-mute">{t('supplierInvoices.empty')}</p>
      ) : (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('supplierInvoices.number')}</th>
                <th className="text-start">{t('purchasing.orders.supplier')}</th>
                <th className="text-start">{t('supplierInvoices.kind')}</th>
                <th className="text-start">{t('supplierInvoices.invoiceDate')}</th>
                <th className="text-start">{t('supplierInvoices.dueDate')}</th>
                <th className="text-end pe-6">{t('supplierInvoices.totalTtc')}</th>
                <th className="text-end pe-6">{t('account.open')}</th>
                <th className="text-start">{t('purchasing.orders.status')}</th>
              </tr>
            </thead>
            <tbody>
              {invoices.data.content.map((i) => (
                <tr key={i.id} className="border-b border-line" data-testid="supplier-invoice">
                  <td className="py-2">
                    <Link to={`/supplier-invoices/${i.id}`} className="text-brand underline underline-offset-4">
                      {i.invoiceNumber}
                    </Link>
                  </td>
                  <td>{i.supplierName}</td>
                  <td>{t(i.creditNote ? 'supplierInvoices.kinds.creditNote' : 'supplierInvoices.kinds.invoice')}</td>
                  <td>{formatDate(i.invoiceDate)}</td>
                  <td>{i.dueDate ? formatDate(i.dueDate) : '—'}</td>
                  <td className="text-end pe-6">{formatMoney(i.totalTtc)}</td>
                  <td className="text-end pe-6">{formatMoney(i.open)}</td>
                  <td>{t(`supplierInvoices.statuses.${i.status}`)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={invoices.data.page} onPage={(next) => update({ page: String(next) })} />
        </>
      )}
    </section>
  )
}

type Field = 'supplierId' | 'invoiceNumber' | 'receiptId' | 'supplierReturnId' | 'invoiceDate' | 'dueDate' | 'totalTtc'

/**
 * `/supplier-invoices/new?supplierId=` (frontend.md §4.5, ADR-133): an invoice or a credit note as the supplier wrote
 * it. The receipt is optional; a credit note may name a supplier return. A total different from the receipt is
 * recorded and shown as a warning on the invoice, not refused.
 */
export function NewSupplierInvoicePage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const record = useRecordSupplierInvoice()
  const suppliers = useSuppliers('', 'true', 0, 100)
  const [values, setValues] = useState<Record<Field, string>>({
    supplierId: params.get('supplierId') ?? '',
    invoiceNumber: '',
    receiptId: '',
    supplierReturnId: '',
    invoiceDate: '',
    dueDate: '',
    totalTtc: '',
  })
  const [creditNote, setCreditNote] = useState(params.get('creditNote') === 'true')
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({})
  const [problem, setProblem] = useState<Problem>()
  const receipts = useReceipts('confirmed', values.supplierId, 0)
  const returns = useSupplierReturns(values.supplierId, 0)
  const set = (field: Field) => (value: string) => setValues((v) => ({ ...v, [field]: value }))
  const id = (value: string) => (value === '' ? null : Number(value))

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setProblem(undefined)
    const missing: Partial<Record<Field, string>> = {}
    for (const field of ['supplierId', 'invoiceNumber', 'invoiceDate', 'totalTtc'] as const) {
      if (values[field].trim() === '') {
        missing[field] = t('fieldErrors.REQUIRED')
      }
    }
    setErrors(missing)
    if (Object.keys(missing).length > 0) {
      return
    }
    const invoice: SupplierInvoiceWrite = {
      supplierId: id(values.supplierId),
      invoiceNumber: values.invoiceNumber.trim(),
      receiptId: id(values.receiptId),
      supplierReturnId: creditNote ? id(values.supplierReturnId) : null,
      invoiceDate: values.invoiceDate,
      dueDate: values.dueDate === '' ? null : values.dueDate,
      totalTtc: values.totalTtc.trim(),
      creditNote,
    }
    record.mutate(invoice, {
      onSuccess: (saved) => navigate(`/supplier-invoices/${saved.id}`, { replace: true, state: { warnings: saved.warnings } }),
      onError: (error) => {
        if (!(error instanceof ApiError)) {
          return
        }
        const byField: Partial<Record<Field, string>> = fieldErrors(error.problem, t)
        if (error.problem.code === 'DUPLICATE_RESOURCE') {
          byField.invoiceNumber = t('fieldErrors.DUPLICATE')
        }
        setErrors(byField)
        if (Object.keys(byField).length === 0) {
          setProblem(error.problem)
        }
      },
    })
  }

  return (
    <section className="flex flex-col gap-4 max-w-2xl">
      <h1 className="text-2xl font-semibold">{t(creditNote ? 'supplierInvoices.newCreditNote' : 'supplierInvoices.newInvoice')}</h1>
      <form onSubmit={submit} noValidate className="flex flex-col gap-3">
        {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
        <fieldset className="flex gap-4 text-sm">
          <legend className="sr-only">{t('supplierInvoices.kind')}</legend>
          <label className="flex items-center gap-2">
            <input type="radio" name="kind" checked={!creditNote} onChange={() => setCreditNote(false)} />
            {t('supplierInvoices.kinds.invoice')}
          </label>
          <label className="flex items-center gap-2">
            <input type="radio" name="kind" checked={creditNote} onChange={() => setCreditNote(true)} />
            {t('supplierInvoices.kinds.creditNote')}
          </label>
        </fieldset>
        <SelectField
          label={t('purchasing.orders.supplier')}
          value={values.supplierId}
          error={errors.supplierId}
          onChange={(e) => setValues((v) => ({ ...v, supplierId: e.target.value, receiptId: '', supplierReturnId: '' }))}
          options={[{ value: '', label: '—' }, ...(suppliers.data?.content ?? []).map((s) => ({ value: String(s.id), label: s.name }))]}
        />
        <TextField label={t('supplierInvoices.number')} value={values.invoiceNumber} error={errors.invoiceNumber} onChange={(e) => set('invoiceNumber')(e.target.value)} />
        <div className="grid grid-cols-2 gap-3">
          <TextField label={t('supplierInvoices.invoiceDate')} type="date" value={values.invoiceDate} error={errors.invoiceDate} onChange={(e) => set('invoiceDate')(e.target.value)} />
          <TextField label={t('supplierInvoices.dueDateOptional')} type="date" value={values.dueDate} error={errors.dueDate} onChange={(e) => set('dueDate')(e.target.value)} />
        </div>
        <TextField label={t('supplierInvoices.totalTtc')} inputMode="decimal" value={values.totalTtc} error={errors.totalTtc} onChange={(e) => set('totalTtc')(e.target.value)} />
        {values.supplierId !== '' && (
          <SelectField
            label={t('supplierInvoices.receipt')}
            value={values.receiptId}
            error={errors.receiptId}
            onChange={(e) => set('receiptId')(e.target.value)}
            options={[{ value: '', label: t('supplierInvoices.none') }, ...(receipts.data?.content ?? []).map((r) => ({ value: String(r.id), label: `${r.number} · ${formatDateTime(r.receivedAt)}` }))]}
          />
        )}
        {creditNote && values.supplierId !== '' && (
          <SelectField
            label={t('supplierInvoices.supplierReturn')}
            value={values.supplierReturnId}
            error={errors.supplierReturnId}
            onChange={(e) => set('supplierReturnId')(e.target.value)}
            options={[{ value: '', label: t('supplierInvoices.none') }, ...(returns.data?.content ?? []).map((r) => ({ value: String(r.id), label: `${r.number} · ${formatDateTime(r.createdAt)}` }))]}
          />
        )}
        <p className="text-xs text-mute">{t('supplierInvoices.dueDateHint')}</p>
        <div className="flex gap-2">
          <Button type="submit" disabled={record.isPending}>
            {t('supplierInvoices.record')}
          </Button>
          <Button type="button" variant="secondary" onClick={() => navigate(-1)}>
            {t('customers.cancel')}
          </Button>
        </div>
      </form>
    </section>
  )
}

/**
 * `/supplier-invoices/:id` (frontend.md §4.5, ADR-133, BR-PAY-015): the document with its open amount, the payments
 * and credit-note applications that settle it; a credit note is applied to open invoices of its supplier; a wrong
 * entry is cancelled with a reason while nothing settles it.
 */
export function SupplierInvoicePage() {
  const { t } = useTranslation()
  const id = Number(useParams().id)
  const location = useLocation()
  const invoice = useSupplierInvoice(id)
  const cancel = useCancelSupplierInvoice(id)
  const [cancelling, setCancelling] = useState(false)
  const [applying, setApplying] = useState(false)
  const warnings = ((location.state as { warnings?: InvoiceWarning[] } | null)?.warnings ?? []).filter((w) => w.code === 'RECEIPT_TOTAL_DIFFERS')

  if (invoice.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (invoice.error instanceof ApiError || !invoice.data) {
    return <Alert tone="danger">{invoice.error instanceof ApiError ? problemMessage(invoice.error.problem, t) : t('errors.UNKNOWN')}</Alert>
  }
  const data = invoice.data
  const recorded = data.status === 'recorded'
  const settled = data.applications.length > 0 || data.payments.length > 0
  return (
    <section className="flex flex-col gap-6 max-w-4xl">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">
          {t(data.creditNote ? 'supplierInvoices.kinds.creditNote' : 'supplierInvoices.kinds.invoice')} {data.invoiceNumber}
        </h1>
        <span className="text-mute" data-testid="invoice-status">
          {t(`supplierInvoices.statuses.${data.status}`)}
        </span>
        <div className="ms-auto flex gap-2">
          {recorded && data.creditNote && data.open !== '0.00' && <Button onClick={() => setApplying(true)}>{t('supplierInvoices.apply')}</Button>}
          {recorded && !settled && (
            <Button variant="secondary" onClick={() => setCancelling(true)}>
              {t('supplierInvoices.cancel')}
            </Button>
          )}
        </div>
      </div>
      {warnings.map((w) => (
        <Alert key={w.code} tone="warn">
          {t('supplierInvoices.receiptDiffers', { value: formatMoney(String(w.context.receiptValue ?? '0')) })}
        </Alert>
      ))}
      <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
        <dt className="text-mute">{t('purchasing.orders.supplier')}</dt>
        <dd>
          <Link to={`/suppliers/${data.supplier.id}`} className="text-brand underline underline-offset-4">
            {data.supplier.name}
          </Link>
        </dd>
        <dt className="text-mute">{t('supplierInvoices.invoiceDate')}</dt>
        <dd>{formatDate(data.invoiceDate)}</dd>
        <dt className="text-mute">{t('supplierInvoices.dueDate')}</dt>
        <dd>{data.dueDate ? formatDate(data.dueDate) : '—'}</dd>
        <dt className="text-mute">{t('supplierInvoices.totalTtc')}</dt>
        <dd>{formatMoney(data.totalTtc)}</dd>
        <dt className="text-mute">{t(data.creditNote ? 'supplierInvoices.unapplied' : 'account.open')}</dt>
        <dd data-testid="invoice-open">{formatMoney(data.open)}</dd>
        {data.receipt && (
          <>
            <dt className="text-mute">{t('supplierInvoices.receipt')}</dt>
            <dd>
              <Link to={`/goods-receipts/${data.receipt.id}`} className="text-brand underline underline-offset-4">
                {data.receipt.number}
              </Link>
            </dd>
          </>
        )}
        {data.supplierReturn && (
          <>
            <dt className="text-mute">{t('supplierInvoices.supplierReturn')}</dt>
            <dd>
              <Link to={`/supplier-returns/${data.supplierReturn.id}`} className="text-brand underline underline-offset-4">
                {data.supplierReturn.number}
              </Link>
            </dd>
          </>
        )}
        {data.cancelReason && (
          <>
            <dt className="text-mute">{t('supplierInvoices.cancelReason')}</dt>
            <dd>
              {data.cancelReason} {data.cancelledAt ? `· ${formatDateTime(data.cancelledAt)}` : ''}
            </dd>
          </>
        )}
      </dl>
      {data.applications.length > 0 && (
        <section className="flex flex-col gap-2" aria-labelledby="applications">
          <h2 id="applications" className="text-lg font-semibold">
            {t(data.creditNote ? 'supplierInvoices.appliedTo' : 'supplierInvoices.creditNotesApplied')}
          </h2>
          <ul className="flex flex-col gap-1 text-sm">
            {data.applications.map((a) => (
              <li key={a.id} className="flex gap-4" data-testid="application">
                <Link to={`/supplier-invoices/${a.otherId}`} className="text-brand underline underline-offset-4">
                  {a.otherNumber}
                </Link>
                <span>{formatMoney(a.amount)}</span>
                <span className="text-mute">{formatDateTime(a.createdAt)}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {data.payments.length > 0 && (
        <section className="flex flex-col gap-2" aria-labelledby="payments">
          <h2 id="payments" className="text-lg font-semibold">
            {t('supplierInvoices.payments')}
          </h2>
          <ul className="flex flex-col gap-1 text-sm">
            {data.payments.map((p) => (
              <li key={p.id} className="flex gap-4" data-testid="invoice-payment">
                <span>{t('supplierPayments.number', { id: p.paymentId })}</span>
                <span>{formatMoney(p.amount)}</span>
                <span className="text-mute">{formatDateTime(p.createdAt)}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {cancelling && (
        <ReasonDialog
          title={t('supplierInvoices.cancelTitle')}
          pending={cancel.isPending}
          error={cancel.error instanceof ApiError ? cancel.error : undefined}
          onClose={() => setCancelling(false)}
          onConfirm={(reason) => cancel.mutate(reason, { onSuccess: () => setCancelling(false) })}
        />
      )}
      {applying && <ApplyCreditNoteDialog creditNote={data} onClose={() => setApplying(false)} />}
    </section>
  )
}

/** Applies a credit note to open invoices of its supplier (ADR-133); what is left stays to apply later. */
function ApplyCreditNoteDialog({ creditNote, onClose }: { creditNote: SupplierInvoiceDetail; onClose: () => void }) {
  const { t } = useTranslation()
  const payables = usePayables(creditNote.supplier.id)
  const apply = useApplyCreditNote()
  const [key] = useState(() => crypto.randomUUID())
  const [amounts, setAmounts] = useState<Record<number, string>>({})
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [problem, setProblem] = useState<Problem>()
  const invoices = (payables.data?.invoices ?? []).map((i) => ({ id: i.id, number: i.invoiceNumber, open: i.open }))

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setProblem(undefined)
    setErrors({})
    apply.mutate(
      { creditNoteId: creditNote.id, allocations: allocationsOf(invoices, amounts), key },
      {
        onSuccess: onClose,
        onError: (error) => {
          if (error instanceof ApiError) {
            const byField = fieldErrors(error.problem, t)
            setErrors(byField)
            if (Object.keys(byField).length === 0) {
              setProblem(error.problem)
            }
          }
        },
      },
    )
  }

  return (
    <Dialog title={t('supplierInvoices.applyTitle', { amount: formatMoney(creditNote.open) })} onClose={onClose}>
      <form onSubmit={submit} noValidate className="flex flex-col gap-3">
        {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
        {payables.isPending ? (
          <p role="status" className="text-mute">
            {t('app.loading')}
          </p>
        ) : invoices.length === 0 ? (
          <p className="text-sm text-mute">{t('account.noOpenInvoice')}</p>
        ) : (
          <AllocationRows invoices={invoices} amounts={amounts} errors={errors} onChange={(id, v) => setAmounts((a) => ({ ...a, [id]: v }))} />
        )}
        <div className="flex gap-2">
          <Button type="submit" disabled={apply.isPending || invoices.length === 0}>
            {t('supplierInvoices.apply')}
          </Button>
          <Button type="button" variant="secondary" onClick={onClose}>
            {t('customers.cancel')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
