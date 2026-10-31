import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatMoney } from '../../lib/format'
import { useCloseSession, useCurrentSession, useOpenSession, type CashSession } from './saleApi'

/** The always-visible cash-session status of the counter (frontend.md §2, ADR-118): open or close the user's own. */
export function CashSessionBar({ opening, onOpening }: { opening: boolean; onOpening: (open: boolean) => void }) {
  const { t } = useTranslation()
  const session = useCurrentSession(true)
  const [closing, setClosing] = useState(false)
  const [closed, setClosed] = useState<CashSession | null>(null)
  return (
    <div className="flex items-center gap-3 text-sm" data-testid="cash-session">
      {session.data ? (
        <>
          <span>{t('pos.session.open', { amount: formatMoney(session.data.expectedAmount) })}</span>
          <Button type="button" variant="secondary" onClick={() => setClosing(true)}>
            {t('pos.session.close')}
          </Button>
        </>
      ) : (
        <>
          <span className="text-mute">{t('pos.session.none')}</span>
          <Button type="button" variant="secondary" onClick={() => onOpening(true)}>
            {t('pos.session.openAction')}
          </Button>
        </>
      )}
      {closed && closed.varianceAmount !== null && (
        <Alert tone="ok">{t('pos.session.closed', { variance: formatMoney(closed.varianceAmount) })}</Alert>
      )}
      {opening && <OpenDialog onClose={() => onOpening(false)} />}
      {closing && session.data && (
        <CloseDialog
          session={session.data}
          onClose={() => setClosing(false)}
          onClosed={(result) => {
            setClosed(result)
            setClosing(false)
          }}
        />
      )}
    </div>
  )
}

function OpenDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const open = useOpenSession()
  const [amount, setAmount] = useState('')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    open.mutate(amount.trim(), { onSuccess: onClose })
  }
  return (
    <Dialog title={t('pos.session.openTitle')} onClose={onClose}>
      <form onSubmit={submit} className="flex flex-col gap-3">
        {open.error instanceof ApiError && <Alert tone="danger">{problemMessage(open.error.problem, t)}</Alert>}
        <TextField label={t('pos.session.openingAmount')} inputMode="decimal" required value={amount} onChange={(e) => setAmount(e.target.value)} />
        <Button type="submit" disabled={open.isPending}>
          {t('pos.session.openAction')}
        </Button>
      </form>
    </Dialog>
  )
}

function CloseDialog({ session, onClose, onClosed }: { session: CashSession; onClose: () => void; onClosed: (result: CashSession) => void }) {
  const { t } = useTranslation()
  const close = useCloseSession()
  const [amount, setAmount] = useState('')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    close.mutate({ id: session.id, countedAmount: amount.trim() }, { onSuccess: onClosed })
  }
  return (
    <Dialog title={t('pos.session.closeTitle')} onClose={onClose}>
      <form onSubmit={submit} className="flex flex-col gap-3">
        {close.error instanceof ApiError && <Alert tone="danger">{problemMessage(close.error.problem, t)}</Alert>}
        <TextField label={t('pos.session.countedAmount')} inputMode="decimal" required value={amount} onChange={(e) => setAmount(e.target.value)} />
        <Button type="submit" disabled={close.isPending}>
          {t('pos.session.close')}
        </Button>
      </form>
    </Dialog>
  )
}
