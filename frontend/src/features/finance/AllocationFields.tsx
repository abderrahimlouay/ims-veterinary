import { useTranslation } from 'react-i18next'
import { TextField } from '../../components/TextField'
import { formatMoney } from '../../lib/format'
import { allocationsOf, type Allocatable } from './allocations'

/**
 * One amount per open document, each labelled with what is left on it; the server checks them all. A field error
 * names its index in the allocations sent (the filled rows only), so it is placed back on that row.
 */
export function AllocationRows({
  invoices,
  amounts,
  errors,
  onChange,
}: {
  invoices: Allocatable[]
  amounts: Record<number, string>
  errors: Record<string, string>
  onChange: (id: number, value: string) => void
}) {
  const { t } = useTranslation()
  const sent = allocationsOf(invoices, amounts).map((a) => a.invoiceId)
  return (
    <div className="flex flex-col gap-2">
      {invoices.map((invoice) => (
        <TextField
          key={invoice.id}
          label={t('account.allocateTo', { number: invoice.number, open: formatMoney(invoice.open) })}
          inputMode="decimal"
          value={amounts[invoice.id] ?? ''}
          error={errors[`allocations[${sent.indexOf(invoice.id)}].amount`]}
          onChange={(e) => onChange(invoice.id, e.target.value)}
        />
      ))}
    </div>
  )
}
