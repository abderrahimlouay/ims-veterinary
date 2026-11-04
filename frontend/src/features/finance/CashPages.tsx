import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { PdfLink } from '../../components/PdfLink'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDateTime, formatMoney } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { useCurrentSession } from '../sales/saleApi'
import { fieldErrors } from './allocations'
import { useAddMovement, useCashReport, useCashSessions, useCloseAnySession, type CashSessionView, type MovementWrite } from './cashApi'

/** "You" for the user's own session, else the cashier's id (the list carries ids only). */
function useCashierLabel() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  return (cashierId: number | null) =>
    cashierId === null ? '—' : current?.user.id === cashierId ? t('cash.you') : t('cash.userId', { id: cashierId })
}

/**
 * `/cash-sessions` (frontend.md §4.9, ADR-136; `cash.manage`): the user's own open session with its manual movements,
 * then every session newest first with its expected, counted and variance amounts; a manager closes any open session.
 */
export function CashSessionsPage() {
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const sessions = useCashSessions(page)
  const own = useCurrentSession(true)
  const cashier = useCashierLabel()
  const [moving, setMoving] = useState(false)
  const [closing, setClosing] = useState<CashSessionView>()

  return (
    <section className="flex flex-col gap-6">
      <h1 className="text-2xl font-semibold">{t('cash.title')}</h1>
      <div className="flex items-center gap-4 text-sm" data-testid="own-session">
        {own.data ? (
          <>
            <span>{t('pos.session.open', { amount: formatMoney(own.data.expectedAmount) })}</span>
            <Button onClick={() => setMoving(true)}>{t('cash.movement')}</Button>
            <Link to={`/cash-sessions/${own.data.id}`} className="text-brand underline underline-offset-4">
              {t('cash.report')}
            </Link>
          </>
        ) : (
          <span className="text-mute">{t('cash.noOwnSession')}</span>
        )}
      </div>
      {sessions.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : sessions.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(sessions.error.problem, t)}</Alert>
      ) : !sessions.data || sessions.data.content.length === 0 ? (
        <p className="text-mute">{t('cash.empty')}</p>
      ) : (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('cash.session')}</th>
                <th className="text-start">{t('cash.cashier')}</th>
                <th className="text-start">{t('cash.openedAt')}</th>
                <th className="text-start">{t('cash.closedAt')}</th>
                <th className="text-end pe-6">{t('cash.expected')}</th>
                <th className="text-end pe-6">{t('cash.counted')}</th>
                <th className="text-end pe-6">{t('cash.variance')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {sessions.data.content.map((s) => (
                <tr key={s.id} className="border-b border-line" data-testid="cash-session-row">
                  <td className="py-2">
                    <Link to={`/cash-sessions/${s.id}`} className="text-brand underline underline-offset-4">
                      {t('cash.number', { id: s.id })}
                    </Link>
                  </td>
                  <td>{cashier(s.cashierId)}</td>
                  <td>{formatDateTime(s.openedAt)}</td>
                  <td>{s.closedAt ? formatDateTime(s.closedAt) : t('cash.statuses.open')}</td>
                  <td className="text-end pe-6">{formatMoney(s.expectedAmount)}</td>
                  <td className="text-end pe-6">{s.countedAmount ? formatMoney(s.countedAmount) : '—'}</td>
                  <td className="text-end pe-6">{s.varianceAmount ? formatMoney(s.varianceAmount) : '—'}</td>
                  <td className="text-end">
                    {s.status === 'open' && (
                      <Button variant="secondary" onClick={() => setClosing(s)}>
                        {t('pos.session.close')}
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={sessions.data.page} onPage={setPage} />
        </>
      )}
      {moving && own.data && <MovementDialog sessionId={own.data.id} onClose={() => setMoving(false)} />}
      {closing && <CloseDialog session={closing} onClose={() => setClosing(undefined)} />}
    </section>
  )
}

/**
 * `/cash-sessions/:id` (frontend.md §4.9, ADR-136; `cash.manage`, or the cashier for their own): the report by source,
 * the cheques and transfers recorded apart (not in the expected cash, BR-CASH-003), expected, counted and variance.
 */
export function CashSessionPage() {
  const { t } = useTranslation()
  const id = Number(useParams().id)
  const { data: current } = useCurrentUser()
  const report = useCashReport(id)
  const cashier = useCashierLabel()
  const [moving, setMoving] = useState(false)
  const [closing, setClosing] = useState(false)

  if (report.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (report.error instanceof ApiError || !report.data) {
    return <Alert tone="danger">{report.error instanceof ApiError ? problemMessage(report.error.problem, t) : t('errors.UNKNOWN')}</Alert>
  }
  const data = report.data
  const open = data.session.status === 'open'
  const mine = current?.user.id === data.session.cashierId
  const manage = hasPermission(current, 'cash.manage')
  return (
    <section className="flex flex-col gap-6 max-w-4xl">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{t('cash.number', { id: data.session.id })}</h1>
        <span className="text-mute" data-testid="session-status">
          {t(`cash.statuses.${data.session.status}`)}
        </span>
        <div className="ms-auto flex gap-2">
          <PdfLink href={`/api/cash-sessions/${data.session.id}/report?format=pdf`}>{t('cash.reportPdf')}</PdfLink>
          {open && mine && manage && <Button onClick={() => setMoving(true)}>{t('cash.movement')}</Button>}
          {open && (mine || manage) && (
            <Button variant="secondary" onClick={() => setClosing(true)}>
              {t('pos.session.close')}
            </Button>
          )}
        </div>
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
        <dt className="text-mute">{t('cash.cashier')}</dt>
        <dd>{cashier(data.session.cashierId)}</dd>
        <dt className="text-mute">{t('cash.openedAt')}</dt>
        <dd>{formatDateTime(data.session.openedAt)}</dd>
        {data.session.closedAt && (
          <>
            <dt className="text-mute">{t('cash.closedAt')}</dt>
            <dd>
              {formatDateTime(data.session.closedAt)} · {t('cash.closedBy', { name: cashier(data.closedBy) })}
            </dd>
          </>
        )}
      </dl>
      <section className="flex flex-col gap-2" aria-labelledby="by-source">
        <h2 id="by-source" className="text-lg font-semibold">
          {t('cash.cash')}
        </h2>
        <table className="w-full text-sm border-collapse max-w-2xl" data-testid="cash-report">
          <thead>
            <tr className="border-b border-line">
              <th className="text-start py-2">{t('cash.source')}</th>
              <th className="text-end pe-6">{t('cash.in')}</th>
              <th className="text-end">{t('cash.out')}</th>
            </tr>
          </thead>
          <tbody>
            <tr className="border-b border-line">
              <td className="py-2">{t('cash.opening')}</td>
              <td className="text-end pe-6">{formatMoney(data.openingAmount)}</td>
              <td />
            </tr>
            {data.bySource.map((s) => (
              <tr key={s.source} className="border-b border-line" data-testid={`source-${s.source}`}>
                <td className="py-2">{t(`cash.sources.${s.source}`)}</td>
                <td className="text-end pe-6">{s.in === '0.00' ? '' : formatMoney(s.in)}</td>
                <td className="text-end">{s.out === '0.00' ? '' : formatMoney(s.out)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-1 text-sm max-w-md">
          <dt>{t('cash.expected')}</dt>
          <dd data-testid="report-expected">{formatMoney(data.expected)}</dd>
          <dt>{t('cash.counted')}</dt>
          <dd>{data.counted ? formatMoney(data.counted) : '—'}</dd>
          <dt>{t('cash.variance')}</dt>
          <dd data-testid="report-variance">{data.variance ? formatMoney(data.variance) : '—'}</dd>
        </dl>
      </section>
      <section className="flex flex-col gap-2" aria-labelledby="non-cash">
        <h2 id="non-cash" className="text-lg font-semibold">
          {t('cash.nonCash')}
        </h2>
        <p className="text-xs text-mute">{t('cash.nonCashHint')}</p>
        {data.nonCash.length === 0 ? (
          <p className="text-sm text-mute">{t('cash.noNonCash')}</p>
        ) : (
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('sales.date')}</th>
                <th className="text-start">{t('cash.kind')}</th>
                <th className="text-start">{t('account.method')}</th>
                <th className="text-start">{t('account.reference')}</th>
                <th className="text-end pe-6">{t('cash.in')}</th>
                <th className="text-end">{t('cash.out')}</th>
              </tr>
            </thead>
            <tbody>
              {data.nonCash.map((n) => (
                <tr key={`${n.kind}-${n.id}`} className="border-b border-line" data-testid="non-cash">
                  <td className="py-2">{formatDateTime(n.at)}</td>
                  <td>
                    {t(`cash.kinds.${n.kind}`)}
                    {n.reversal && <span className="text-mute"> · {t('cash.reversal')}</span>}
                  </td>
                  <td>{t(`pos.methods.${n.method}`)}</td>
                  <td>{n.reference ?? '—'}</td>
                  <td className="text-end pe-6">{n.direction === 'in' ? formatMoney(n.amount) : ''}</td>
                  <td className="text-end">{n.direction === 'out' ? formatMoney(n.amount) : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {moving && <MovementDialog sessionId={data.session.id} onClose={() => setMoving(false)} />}
      {closing && <CloseDialog session={data.session} onClose={() => setClosing(false)} />}
    </section>
  )
}

/** A manual cash in or out on the user's own open session (ADR-136): the amount and a mandatory label. */
function MovementDialog({ sessionId, onClose }: { sessionId: number; onClose: () => void }) {
  const { t } = useTranslation()
  const add = useAddMovement(sessionId)
  const [key] = useState(() => crypto.randomUUID())
  const [values, setValues] = useState<MovementWrite>({ direction: 'out', amount: '', label: '' })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [problem, setProblem] = useState<Problem>()

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setProblem(undefined)
    const missing: Record<string, string> = {}
    if (values.amount.trim() === '') {
      missing.amount = t('fieldErrors.REQUIRED')
    }
    if (values.label.trim() === '') {
      missing.label = t('fieldErrors.REQUIRED')
    }
    setErrors(missing)
    if (Object.keys(missing).length > 0) {
      return
    }
    add.mutate(
      { movement: { direction: values.direction, amount: values.amount.trim(), label: values.label.trim() }, key },
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
    <Dialog title={t('cash.movement')} onClose={onClose}>
      <form onSubmit={submit} noValidate className="flex flex-col gap-3">
        {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
        <SelectField
          label={t('cash.direction')}
          value={values.direction}
          onChange={(e) => setValues((v) => ({ ...v, direction: e.target.value as MovementWrite['direction'] }))}
          options={[
            { value: 'in', label: t('cash.directions.in') },
            { value: 'out', label: t('cash.directions.out') },
          ]}
        />
        <TextField
          label={t('account.amount')}
          inputMode="decimal"
          value={values.amount}
          error={errors.amount}
          onChange={(e) => setValues((v) => ({ ...v, amount: e.target.value }))}
        />
        <TextField
          label={t('cash.label')}
          maxLength={200}
          value={values.label}
          error={errors.label}
          onChange={(e) => setValues((v) => ({ ...v, label: e.target.value }))}
        />
        <div className="flex gap-2">
          <Button type="submit" disabled={add.isPending}>
            {t('cash.post')}
          </Button>
          <Button type="button" variant="secondary" onClick={onClose}>
            {t('customers.cancel')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/** Closing with the counted amount; the server computes expected and variance (BR-CASH-002/003). */
function CloseDialog({ session, onClose }: { session: CashSessionView; onClose: () => void }) {
  const { t } = useTranslation()
  const close = useCloseAnySession(session.id)
  const [amount, setAmount] = useState('')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    close.mutate(amount.trim(), { onSuccess: onClose })
  }
  return (
    <Dialog title={t('cash.closeTitle', { id: session.id })} onClose={onClose}>
      <form onSubmit={submit} noValidate className="flex flex-col gap-3">
        {close.error instanceof ApiError && <Alert tone="danger">{problemMessage(close.error.problem, t)}</Alert>}
        <TextField label={t('pos.session.countedAmount')} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <div className="flex gap-2">
          <Button type="submit" disabled={close.isPending}>
            {t('pos.session.close')}
          </Button>
          <Button type="button" variant="secondary" onClick={onClose}>
            {t('customers.cancel')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
