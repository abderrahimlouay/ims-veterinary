import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
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
import { hasPermission, useCurrentUser } from '../auth'
import { ReasonDialog } from '../purchasing/OrderDetailPage'
import { PAYMENT_METHODS, type PaymentMethod } from '../sales/saleApi'
import { AllocationRows } from './AllocationFields'
import { allocationsOf, fieldErrors, type Allocatable } from './allocations'
import {
  useApplySupplierAdvance,
  usePayables,
  useRecordSupplierPayment,
  useReverseSupplierPayment,
  useSupplierPayments,
  type Payables,
} from './supplierApi'

/**
 * The Payables tab of a supplier (frontend.md §4.5, ADR-132, BR-PAY-014): what we owe, the open invoices by due date,
 * the credit notes left to apply and the advances (`payment.read`); recording a payment and applying an advance
 * (`payment.supplier.create`); the payments with their reversal (`payment.reverse`); invoices and credit notes
 * (`supplierinvoice.manage`). Each control appears only with its permission; the server refuses anyway (BR-SEC-002).
 */
export function SupplierPayablesPanel({ supplierId }: { supplierId: number }) {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const canPay = hasPermission(current, 'payment.supplier.create')
  const canReverse = hasPermission(current, 'payment.reverse')
  const canInvoice = hasPermission(current, 'supplierinvoice.manage')
  const payables = usePayables(supplierId)
  const [paying, setPaying] = useState(false)
  const [applying, setApplying] = useState<Payables['advances'][number]>()

  if (payables.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (payables.error instanceof ApiError || !payables.data) {
    return <Alert tone="danger">{payables.error instanceof ApiError ? problemMessage(payables.error.problem, t) : t('errors.UNKNOWN')}</Alert>
  }
  const data = payables.data
  const invoices: Allocatable[] = data.invoices.map((i) => ({ id: i.id, number: i.invoiceNumber, open: i.open }))
  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start gap-4">
        <dl className="text-sm">
          <dt className="text-mute">{t('supplierPayments.balance')}</dt>
          <dd className="text-xl font-semibold" data-testid="supplier-balance">
            {formatMoney(data.balance)}
          </dd>
        </dl>
        <div className="ms-auto flex gap-2">
          {canInvoice && (
            <Link
              to={`/supplier-invoices/new?supplierId=${supplierId}`}
              className="h-10 px-4 inline-flex items-center rounded-ctl border border-control text-sm font-medium"
            >
              {t('supplierInvoices.new')}
            </Link>
          )}
          {canPay && <Button onClick={() => setPaying(true)}>{t('supplierPayments.pay')}</Button>}
        </div>
      </div>
      <section className="flex flex-col gap-2" aria-labelledby="payable-invoices">
        <h2 id="payable-invoices" className="text-lg font-semibold">
          {t('account.openInvoices')}
        </h2>
        {data.invoices.length === 0 ? (
          <p className="text-mute text-sm">{t('account.noOpenInvoice')}</p>
        ) : (
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('supplierInvoices.number')}</th>
                <th className="text-start">{t('supplierInvoices.invoiceDate')}</th>
                <th className="text-start">{t('supplierInvoices.dueDate')}</th>
                <th className="text-end pe-6">{t('supplierInvoices.totalTtc')}</th>
                <th className="text-end">{t('account.open')}</th>
              </tr>
            </thead>
            <tbody>
              {data.invoices.map((i) => (
                <tr key={i.id} className="border-b border-line" data-testid="payable-invoice">
                  <td className="py-2">
                    {canInvoice ? (
                      <Link to={`/supplier-invoices/${i.id}`} className="underline">
                        {i.invoiceNumber}
                      </Link>
                    ) : (
                      i.invoiceNumber
                    )}
                  </td>
                  <td>{formatDate(i.invoiceDate)}</td>
                  <td>{i.dueDate ? formatDate(i.dueDate) : '—'}</td>
                  <td className="text-end pe-6">{formatMoney(i.totalTtc)}</td>
                  <td className="text-end">{formatMoney(i.open)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {data.creditNotes.length > 0 && (
        <section className="flex flex-col gap-2" aria-labelledby="credit-notes">
          <h2 id="credit-notes" className="text-lg font-semibold">
            {t('supplierPayments.creditNotes')}
          </h2>
          <ul className="flex flex-col gap-2 text-sm">
            {data.creditNotes.map((c) => (
              <li key={c.id} className="flex items-center gap-4" data-testid="unapplied-credit-note">
                {canInvoice ? (
                  <Link to={`/supplier-invoices/${c.id}`} className="underline">
                    {c.invoiceNumber}
                  </Link>
                ) : (
                  <span>{c.invoiceNumber}</span>
                )}
                <strong>{formatMoney(c.unapplied)}</strong>
              </li>
            ))}
          </ul>
        </section>
      )}
      {data.advances.length > 0 && (
        <section className="flex flex-col gap-2" aria-labelledby="supplier-advances">
          <h2 id="supplier-advances" className="text-lg font-semibold">
            {t('account.advances')}
          </h2>
          <ul className="flex flex-col gap-2 text-sm">
            {data.advances.map((a) => (
              <li key={a.paymentId} className="flex items-center gap-4" data-testid="supplier-advance">
                <span>{t('account.advanceOf', { date: formatDateTime(a.paidAt) })}</span>
                <strong>{formatMoney(a.unallocated)}</strong>
                {canPay && data.invoices.length > 0 && (
                  <Button variant="secondary" onClick={() => setApplying(a)}>
                    {t('account.apply')}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}
      <PaymentsSection supplierId={supplierId} canReverse={canReverse} />
      {paying && <PaymentDialog supplierId={supplierId} invoices={invoices} onClose={() => setPaying(false)} />}
      {applying && <ApplyAdvanceDialog advance={applying} invoices={invoices} onClose={() => setApplying(undefined)} />}
    </div>
  )
}

function PaymentsSection({ supplierId, canReverse }: { supplierId: number; canReverse: boolean }) {
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const payments = useSupplierPayments(supplierId, page)
  const [reversing, setReversing] = useState<number>()
  if (!payments.data || payments.data.content.length === 0) {
    return null
  }
  return (
    <section className="flex flex-col gap-2" aria-labelledby="supplier-payments">
      <h2 id="supplier-payments" className="text-lg font-semibold">
        {t('supplierInvoices.payments')}
      </h2>
      <table className="w-full text-sm border-collapse">
        <thead>
          <tr className="border-b border-line">
            <th className="text-start py-2">{t('sales.date')}</th>
            <th className="text-start">{t('account.method')}</th>
            <th className="text-start">{t('account.reference')}</th>
            <th className="text-end pe-6">{t('account.amount')}</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {payments.data.content.map((p) => (
            <tr key={p.id} className="border-b border-line" data-testid="supplier-payment">
              <td className="py-2">{formatDateTime(p.paidAt)}</td>
              <td>
                {t(`pos.methods.${p.method}`)}
                {p.reverses !== null && <span className="text-mute"> · {t('supplierPayments.reversalOf', { id: p.reverses })}</span>}
                {p.reversed && <span className="text-mute"> · {t('supplierPayments.reversed')}</span>}
              </td>
              <td>{p.reference ?? '—'}</td>
              <td className="text-end pe-6">{formatMoney(p.amount)}</td>
              <td className="text-end">
                {canReverse && p.reverses === null && !p.reversed && (
                  <Button variant="secondary" onClick={() => setReversing(p.id)}>
                    {t('account.reverse')}
                  </Button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <Pager page={payments.data.page} onPage={setPage} />
      {reversing !== undefined && <ReverseDialog paymentId={reversing} onClose={() => setReversing(undefined)} />}
    </section>
  )
}

function PaymentDialog({ supplierId, invoices, onClose }: { supplierId: number; invoices: Allocatable[]; onClose: () => void }) {
  const { t } = useTranslation()
  const record = useRecordSupplierPayment()
  const [key] = useState(() => crypto.randomUUID())
  const [method, setMethod] = useState<PaymentMethod>('bank_transfer')
  const [amount, setAmount] = useState('')
  const [reference, setReference] = useState('')
  const [manual, setManual] = useState(false)
  const [amounts, setAmounts] = useState<Record<number, string>>({})
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [problem, setProblem] = useState<Problem>()

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setProblem(undefined)
    setErrors({})
    record.mutate(
      {
        key,
        payment: {
          supplierId,
          method,
          amount: amount.trim(),
          reference: method === 'cash' || reference.trim() === '' ? null : reference.trim(),
          ...(manual ? { allocations: allocationsOf(invoices, amounts) } : {}),
        },
      },
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
    <Dialog title={t('supplierPayments.pay')} onClose={onClose}>
      <form onSubmit={submit} noValidate className="flex flex-col gap-3">
        {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
        <SelectField
          label={t('account.method')}
          hint={t('help.account.method')}
          value={method}
          onChange={(e) => setMethod(e.target.value as PaymentMethod)}
          options={PAYMENT_METHODS.map((m) => ({ value: m, label: t(`pos.methods.${m}`) }))}
        />
        <TextField label={t('account.amount')} inputMode="decimal" value={amount} error={errors.amount} onChange={(e) => setAmount(e.target.value)} />
        {method === 'cash' ? (
          <p className="text-xs text-mute">{t('supplierPayments.cashHint')}</p>
        ) : (
          <TextField label={t('account.reference')} hint={t('help.account.reference')} value={reference} error={errors.reference} onChange={(e) => setReference(e.target.value)} />
        )}
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium">{t('account.split')}</legend>
          <label className="flex items-center gap-2 text-sm">
            <input type="radio" name="split" checked={!manual} onChange={() => setManual(false)} />
            {t('account.oldestFirst')}
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input type="radio" name="split" checked={manual} onChange={() => setManual(true)} />
            {t('account.chooseInvoices')}
          </label>
        </fieldset>
        {manual &&
          (invoices.length === 0 ? (
            <p className="text-sm text-mute">{t('account.allAdvance')}</p>
          ) : (
            <AllocationRows invoices={invoices} amounts={amounts} errors={errors} onChange={(id, v) => setAmounts((a) => ({ ...a, [id]: v }))} />
          ))}
        <p className="text-xs text-mute">{t('account.restIsAdvance')}</p>
        <div className="flex gap-2">
          <Button type="submit" disabled={record.isPending}>
            {t('account.record')}
          </Button>
          <Button type="button" variant="secondary" onClick={onClose}>
            {t('customers.cancel')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

function ApplyAdvanceDialog({
  advance,
  invoices,
  onClose,
}: {
  advance: Payables['advances'][number]
  invoices: Allocatable[]
  onClose: () => void
}) {
  const { t } = useTranslation()
  const apply = useApplySupplierAdvance()
  const [key] = useState(() => crypto.randomUUID())
  const [amounts, setAmounts] = useState<Record<number, string>>({})
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [problem, setProblem] = useState<Problem>()

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setProblem(undefined)
    setErrors({})
    apply.mutate(
      { paymentId: advance.paymentId, allocations: allocationsOf(invoices, amounts), key },
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
    <Dialog title={t('account.applyTitle', { amount: formatMoney(advance.unallocated) })} onClose={onClose}>
      <form onSubmit={submit} noValidate className="flex flex-col gap-3">
        {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
        <AllocationRows invoices={invoices} amounts={amounts} errors={errors} onChange={(id, v) => setAmounts((a) => ({ ...a, [id]: v }))} />
        <div className="flex gap-2">
          <Button type="submit" disabled={apply.isPending}>
            {t('account.apply')}
          </Button>
          <Button type="button" variant="secondary" onClick={onClose}>
            {t('customers.cancel')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/** A reversal with its mandatory reason (ADR-135); one key per dialog so a retry never reverses twice. */
function ReverseDialog({ paymentId, onClose }: { paymentId: number; onClose: () => void }) {
  const { t } = useTranslation()
  const reverse = useReverseSupplierPayment()
  const [key] = useState(() => crypto.randomUUID())
  return (
    <ReasonDialog
      title={t('supplierPayments.reverseTitle')}
      pending={reverse.isPending}
      error={reverse.error instanceof ApiError ? reverse.error : undefined}
      onClose={onClose}
      onConfirm={(reason) => reverse.mutate({ paymentId, reason, key }, { onSuccess: onClose })}
    />
  )
}
