import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { problemMessage } from '../../lib/errors'
import { formatDateTime, formatQuantity } from '../../lib/format'
import { useIntegrityRun, useRunIntegrityCheck } from './stockApi'

/**
 * `/stock/integrity` (`audit.read`, ADR-086): the latest integrity run — lots checked and every lot whose balance
 * differs from the sum of its movements — and a run on demand. The check only reports; it never corrects.
 */
export function IntegrityPage() {
  const { t } = useTranslation()
  const run = useIntegrityRun(true)
  const runNow = useRunIntegrityCheck()
  return (
    <section className="flex flex-col gap-4 max-w-3xl">
      <div className="flex items-center gap-3">
        <h1 className="text-2xl font-semibold">{t('stock.integrity.title')}</h1>
        <Button className="ms-auto" disabled={runNow.isPending} onClick={() => runNow.mutate()}>
          {t('stock.integrity.runNow')}
        </Button>
      </div>
      {runNow.error instanceof ApiError && <Alert tone="danger">{problemMessage(runNow.error.problem, t)}</Alert>}
      {run.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : run.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(run.error.problem, t)}</Alert>
      ) : !run.data ? (
        <p className="text-mute">{t('stock.integrity.none')}</p>
      ) : (
        <>
          <p className="text-sm">
            {t('stock.integrity.summary', {
              at: formatDateTime(run.data.startedAt),
              count: run.data.lotsChecked,
              by: run.data.triggeredBy?.fullName ?? t('stock.integrity.nightly'),
            })}
          </p>
          {run.data.mismatchCount === 0 ? (
            <Alert tone="ok">{t('stock.integrity.clean')}</Alert>
          ) : (
            <table className="w-full text-sm border-collapse">
              <thead>
                <tr className="border-b border-line">
                  <th className="text-start py-2">{t('lots.lotNumber')}</th>
                  <th className="text-end">{t('stock.integrity.balance')}</th>
                  <th className="text-end">{t('stock.integrity.ledgerSum')}</th>
                </tr>
              </thead>
              <tbody>
                {run.data.mismatches.map((m) => (
                  <tr key={m.lotId} className="border-b border-line">
                    <td className="py-2">
                      <Link to={`/lots/${m.lotId}`} className="text-brand underline underline-offset-4">
                        {m.lotNumber}
                      </Link>
                    </td>
                    <td className="text-end">{formatQuantity(m.balance)}</td>
                    <td className="text-end">{formatQuantity(m.ledgerSum)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  )
}
