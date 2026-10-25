import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatMoney } from '../../lib/format'
import { creditLimitPayload, creditLimitState, type CreditLimitState } from './creditLimit'
import { useChangeCreditLimit, type CustomerDetail } from './customerApi'

const STATES: readonly CreditLimitState[] = ['none', 'cash', 'limit']

/** The current credit limit in words: "No limit", "Cash only" or "Limit: amount" (ADR-108). */
export function CreditLimitLabel({ limit }: { limit: string | null }) {
  const { t } = useTranslation()
  const state = creditLimitState(limit)
  return <>{state === 'limit' && limit !== null ? t('customers.credit.limitOf', { amount: formatMoney(limit) }) : t(`customers.credit.${state}`)}</>
}

/**
 * The credit-limit dialog (ADR-108, `customer.credit.manage`): one explicit choice among no limit (sends `null`),
 * cash only (sends `"0.00"`) and a limit with its amount (> 0; zero is cash only). The server checks the
 * permission, the amount and the version anyway.
 */
export function CreditLimitDialog({ customer, onClose }: { customer: CustomerDetail; onClose: () => void }) {
  const { t } = useTranslation()
  const name = useId()
  const change = useChangeCreditLimit(customer.id)
  const [state, setState] = useState<CreditLimitState>(creditLimitState(customer.creditLimit))
  const [amount, setAmount] = useState(creditLimitState(customer.creditLimit) === 'limit' ? (customer.creditLimit ?? '') : '')
  const [error, setError] = useState<string>()

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setError(undefined)
    if (state === 'limit') {
      const value = amount.trim().replace(',', '.')
      if (value === '') {
        setError(t('fieldErrors.REQUIRED'))
        return
      }
      if (!/^\d+(\.\d{1,2})?$/.test(value)) {
        setError(t('customers.credit.amountFormat'))
        return
      }
      if (creditLimitState(value) === 'cash') {
        setError(t('customers.credit.zeroIsCash'))
        return
      }
    }
    change.mutate(
      { creditLimit: creditLimitPayload(state, amount), version: customer.version },
      {
        onSuccess: onClose,
        onError: (e) => {
          if (e instanceof ApiError) {
            const field = e.problem.errors.find((v) => v.field === 'creditLimit')
            if (field) {
              setError(t(`fieldErrors.${field.code}`))
            }
          }
        },
      },
    )
  }

  return (
    <Dialog title={t('customers.credit.title')} onClose={onClose}>
      <form onSubmit={submit} noValidate className="flex flex-col gap-4">
        <p className="text-sm text-mute">
          {t('customers.credit.current')} <strong className="text-ink"><CreditLimitLabel limit={customer.creditLimit} /></strong>
        </p>
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium mb-1">{t('customers.credit.choose')}</legend>
          {STATES.map((s) => (
            <label key={s} className="flex items-start gap-2 text-sm">
              <input type="radio" name={name} value={s} checked={state === s} onChange={() => setState(s)} className="mt-1" />
              <span>
                <span className="font-medium">{t(`customers.credit.option.${s}`)}</span>
                <span className="block text-mute">{t(`customers.credit.explain.${s}`)}</span>
              </span>
            </label>
          ))}
        </fieldset>
        {state === 'limit' && (
          <TextField label={t('customers.credit.amount')} inputMode="decimal" dir="ltr" value={amount} error={error} onChange={(e) => setAmount(e.target.value)} />
        )}
        {state !== 'limit' && error && <Alert tone="danger">{error}</Alert>}
        {change.error instanceof ApiError && !change.error.problem.errors.some((v) => v.field === 'creditLimit') && (
          <Alert tone="danger">{problemMessage(change.error.problem, t)}</Alert>
        )}
        <div className="flex gap-2 justify-end">
          <Button variant="secondary" onClick={onClose}>
            {t('customers.cancel')}
          </Button>
          <Button type="submit" disabled={change.isPending}>
            {t('customers.credit.save')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
