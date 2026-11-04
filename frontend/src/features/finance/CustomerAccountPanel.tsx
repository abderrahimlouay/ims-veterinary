import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatDateTime, formatMoney } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { CustomerBalance } from '../sales'
import { PAYMENT_METHODS, type PaymentMethod } from '../sales/saleApi'
import {
  useApplyAdvance,
  useOpenItems,
  useRecordPayment,
  useReversePayment,
  useStatement,
  type Advance,
  type AllocationWrite,
  type OpenInvoice,
} from './accountApi'

/**
 * The Account tab of a customer (frontend.md §4.6, W8, ADR-134, ADR-135): the balance, the open credit invoices and
 * the advances (`payment.read`), a payment on account and applying an advance (`payment.customer.create`), reversing a
 * payment (`payment.reverse`) and the statement over a period (`statement.read`). Each control appears only with its
 * permission; the server refuses anyway (BR-SEC-002).
 */
export function CustomerAccountPanel({ customerId }: { customerId: number }) {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const canRead = hasPermission(current, 'payment.read')
  const canPay = hasPermission(current, 'payment.customer.create')
  const canReverse = hasPermission(current, 'payment.reverse')
  const canStatement = hasPermission(current, 'statement.read')
  const open = useOpenItems(customerId, canRead)
  const [paying, setPaying] = useState(false)
  const [applying, setApplying] = useState<Advance>()
  const [reversing, setReversing] = useState<number>()

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start gap-4">
        <CustomerBalance customerId={customerId} />
        {canPay && (
          <Button className="ms-auto" onClick={() => setPaying(true)}>
            {t('account.pay')}
          </Button>
        )}
      </div>
      {canRead && open.data && (
        <>
          <section className="flex flex-col gap-2" aria-labelledby="open-invoices">
            <h2 id="open-invoices" className="text-lg font-semibold">
              {t('account.openInvoices')}
            </h2>
            {open.data.invoices.length === 0 ? (
              <p className="text-mute text-sm">{t('account.noOpenInvoice')}</p>
            ) : (
              <table className="w-full text-sm border-collapse">
                <thead>
                  <tr className="border-b border-line">
                    <th className="text-start py-2">{t('sales.number')}</th>
                    <th className="text-start">{t('sales.dueDate')}</th>
                    <th className="text-end pe-6">{t('sales.total')}</th>
                    <th className="text-end">{t('account.open')}</th>
                  </tr>
                </thead>
                <tbody>
                  {open.data.invoices.map((i) => (
                    <tr key={i.id} className="border-b border-line" data-testid="open-invoice">
                      <td className="py-2">
                        <Link to={`/sales/${i.id}`} className="underline">
                          {i.number}
                        </Link>
                      </td>
                      <td>{i.dueDate ? formatDate(i.dueDate) : '—'}</td>
                      <td className="text-end pe-6">{formatMoney(i.totalTtc)}</td>
                      <td className="text-end">{formatMoney(i.open)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
          {open.data.advances.length > 0 && (
            <section className="flex flex-col gap-2" aria-labelledby="advances">
              <h2 id="advances" className="text-lg font-semibold">
                {t('account.advances')}
              </h2>
              <ul className="flex flex-col gap-2 text-sm">
                {open.data.advances.map((a) => (
                  <li key={a.paymentId} className="flex items-center gap-4" data-testid="advance">
                    <span>{t('account.advanceOf', { date: formatDateTime(a.paidAt) })}</span>
                    <strong>{formatMoney(a.unallocated)}</strong>
                    {canPay && open.data.invoices.length > 0 && (
                      <Button variant="secondary" onClick={() => setApplying(a)}>
                        {t('account.apply')}
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            </section>
          )}
        </>
      )}
      {canStatement && <StatementSection customerId={customerId} onReverse={canReverse ? setReversing : undefined} />}
      {paying && <PaymentDialog customerId={customerId} invoices={open.data?.invoices ?? []} onClose={() => setPaying(false)} />}
      {applying && (
        <ApplyDialog customerId={customerId} advance={applying} invoices={open.data?.invoices ?? []} onClose={() => setApplying(undefined)} />
      )}
      {reversing !== undefined && <ReverseDialog customerId={customerId} paymentId={reversing} onClose={() => setReversing(undefined)} />}
    </div>
  )
}

function StatementSection({ customerId, onReverse }: { customerId: number; onReverse?: (paymentId: number) => void }) {
  const { t } = useTranslation()
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const statement = useStatement(customerId, from, to)
  return (
    <section className="flex flex-col gap-3" aria-labelledby="statement">
      <h2 id="statement" className="text-lg font-semibold">
        {t('account.statement')}
      </h2>
      <div className="flex gap-3 items-end max-w-md">
        <TextField label={t('account.from')} type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        <TextField label={t('account.to')} type="date" value={to} onChange={(e) => setTo(e.target.value)} />
      </div>
      {statement.error instanceof ApiError && <Alert tone="danger">{problemMessage(statement.error.problem, t)}</Alert>}
      {statement.data && (
        <>
          <table className="w-full text-sm border-collapse" data-testid="statement">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('sales.date')}</th>
                <th className="text-start">{t('account.entry')}</th>
                <th className="text-end pe-6">{t('account.debit')}</th>
                <th className="text-end pe-6">{t('account.credit')}</th>
                <th className="text-end pe-6">{t('account.balance')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr className="border-b border-line">
                <td className="py-2" colSpan={4}>
                  {t('account.opening')}
                </td>
                <td className="text-end pe-6">{formatMoney(statement.data.openingBalance)}</td>
                <td />
              </tr>
              {statement.data.lines.map((l) => (
                <tr key={`${l.kind}-${l.documentId ?? l.paymentId}`} className="border-b border-line" data-testid="statement-line">
                  <td className="py-2">{formatDate(l.date)}</td>
                  <td>
                    {t(`account.kinds.${l.kind}`)}{' '}
                    {l.documentId !== null ? (
                      <Link to={`/sales/${l.documentId}`} className="underline">
                        {l.number}
                      </Link>
                    ) : (
                      <span className="text-mute">
                        {l.method ? t(`pos.methods.${l.method}`) : ''}
                        {l.reference ? ` · ${l.reference}` : ''}
                      </span>
                    )}
                  </td>
                  <td className="text-end pe-6">{l.debit === '0.00' ? '' : formatMoney(l.debit)}</td>
                  <td className="text-end pe-6">{l.credit === '0.00' ? '' : formatMoney(l.credit)}</td>
                  <td className="text-end pe-6">{formatMoney(l.balance)}</td>
                  <td className="text-end">
                    {onReverse && l.kind === 'payment' && l.paymentId !== null && (
                      <Button variant="secondary" onClick={() => onReverse(l.paymentId as number)}>
                        {t('account.reverse')}
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm max-w-md">
            <dt>{t('account.closing')}</dt>
            <dd data-testid="closing-balance">{formatMoney(statement.data.closingBalance)}</dd>
            <dt>{t('sales.balance.uninvoiced')}</dt>
            <dd>{formatMoney(statement.data.uninvoicedDeliveryNotes)}</dd>
          </dl>
        </>
      )}
    </section>
  )
}

/** The field errors of a failed payment, by field name, and the problem when none is a field (api.md §3). */
function fieldErrors(problem: Problem, t: (key: string) => string): Record<string, string> {
  const errors: Record<string, string> = {}
  for (const e of problem.errors) {
    errors[e.field] = t(`fieldErrors.${e.code}`)
  }
  return errors
}

function AllocationRows({
  invoices,
  amounts,
  errors,
  onChange,
}: {
  invoices: OpenInvoice[]
  amounts: Record<number, string>
  errors: Record<string, string>
  onChange: (id: number, value: string) => void
}) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col gap-2">
      {invoices.map((invoice, index) => (
        <TextField
          key={invoice.id}
          label={t('account.allocateTo', { number: invoice.number, open: formatMoney(invoice.open) })}
          inputMode="decimal"
          value={amounts[invoice.id] ?? ''}
          error={errors[`allocations[${index}].amount`]}
          onChange={(e) => onChange(invoice.id, e.target.value)}
        />
      ))}
    </div>
  )
}

function allocationsOf(invoices: OpenInvoice[], amounts: Record<number, string>): AllocationWrite[] {
  return invoices.filter((i) => (amounts[i.id] ?? '').trim() !== '').map((i) => ({ invoiceId: i.id, amount: amounts[i.id].trim() }))
}

function PaymentDialog({ customerId, invoices, onClose }: { customerId: number; invoices: OpenInvoice[]; onClose: () => void }) {
  const { t } = useTranslation()
  const record = useRecordPayment(customerId)
  const [key] = useState(() => crypto.randomUUID())
  const [method, setMethod] = useState<PaymentMethod>('cheque')
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
          customerId,
          method,
          amount: amount.trim(),
          reference: reference.trim() === '' ? null : reference.trim(),
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
    <Dialog title={t('account.pay')} onClose={onClose}>
      <form onSubmit={submit} noValidate className="flex flex-col gap-3">
        {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
        <SelectField
          label={t('account.method')}
          value={method}
          onChange={(e) => setMethod(e.target.value as PaymentMethod)}
          options={PAYMENT_METHODS.map((m) => ({ value: m, label: t(`pos.methods.${m}`) }))}
        />
        <TextField label={t('account.amount')} inputMode="decimal" value={amount} error={errors.amount} onChange={(e) => setAmount(e.target.value)} />
        {method !== 'cash' && (
          <TextField label={t('account.reference')} value={reference} error={errors.reference} onChange={(e) => setReference(e.target.value)} />
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

function ApplyDialog({ customerId, advance, invoices, onClose }: { customerId: number; advance: Advance; invoices: OpenInvoice[]; onClose: () => void }) {
  const { t } = useTranslation()
  const apply = useApplyAdvance(customerId)
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

function ReverseDialog({ customerId, paymentId, onClose }: { customerId: number; paymentId: number; onClose: () => void }) {
  const { t } = useTranslation()
  const reverse = useReversePayment(customerId)
  const [key] = useState(() => crypto.randomUUID())
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const [problem, setProblem] = useState<Problem>()

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setProblem(undefined)
    if (reason.trim() === '') {
      setError(t('fieldErrors.REQUIRED'))
      return
    }
    setError(undefined)
    reverse.mutate(
      { paymentId, reason: reason.trim(), key },
      {
        onSuccess: onClose,
        onError: (e) => {
          if (e instanceof ApiError) {
            setProblem(e.problem)
          }
        },
      },
    )
  }

  return (
    <Dialog title={t('account.reverseTitle')} onClose={onClose}>
      <form onSubmit={submit} noValidate className="flex flex-col gap-3">
        <p className="text-sm text-mute">{t('account.reverseHint')}</p>
        {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
        <TextField label={t('account.reason')} maxLength={500} value={reason} error={error} onChange={(e) => setReason(e.target.value)} />
        <div className="flex gap-2">
          <Button type="submit" disabled={reverse.isPending}>
            {t('account.reverse')}
          </Button>
          <Button type="button" variant="secondary" onClick={onClose}>
            {t('customers.cancel')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
