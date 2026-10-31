import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Pager } from '../../components/Pager'
import { problemMessage } from '../../lib/errors'
import { formatMoney } from '../../lib/format'
import { SalesTable } from './SalesTable'
import { useCreditBalance, useCustomerSales } from './saleApi'

/** The balance on a customer's Overview (ADR-118); uninvoiced delivery notes are shown apart (ADR-117). */
export function CustomerBalance({ customerId }: { customerId: number }) {
  const { t } = useTranslation()
  const balance = useCreditBalance(customerId, true)
  if (!balance.data) {
    return null
  }
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm max-w-xl">
      <dt>{t('sales.balance.balance')}</dt>
      <dd data-testid="customer-balance">{formatMoney(balance.data.balance)}</dd>
      <dt>{t('sales.balance.uninvoiced')}</dt>
      <dd>{formatMoney(balance.data.uninvoicedDeliveryNotes)}</dd>
      {balance.data.availableCredit !== null && (
        <>
          <dt>{t('sales.balance.available')}</dt>
          <dd>{formatMoney(balance.data.availableCredit)}</dd>
        </>
      )}
    </dl>
  )
}

/** The History tab of a customer (frontend.md §4.6), `sale.read`. */
export function CustomerSalesPanel({ customerId }: { customerId: number }) {
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const sales = useCustomerSales(customerId, page, true)
  if (sales.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (sales.error instanceof ApiError) {
    return <Alert tone="danger">{problemMessage(sales.error.problem, t)}</Alert>
  }
  if (!sales.data || sales.data.content.length === 0) {
    return <p className="text-mute">{t('sales.empty')}</p>
  }
  return (
    <div className="flex flex-col gap-4">
      <SalesTable sales={sales.data.content} />
      <Pager page={sales.data.page} onPage={setPage} />
    </div>
  )
}
