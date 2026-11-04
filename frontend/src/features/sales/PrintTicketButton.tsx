import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { problemMessage } from '../../lib/errors'
import { printInFrame, salePdfUrl } from './printing'

/** "Imprimer le ticket" (ADR-139): the 80 mm ticket printed at once; a refusal is shown under the button. */
export function PrintTicketButton({ saleId }: { saleId: number }) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<Problem>()
  const print = () => {
    setBusy(true)
    setProblem(undefined)
    printInFrame(salePdfUrl(saleId, 'ticket'))
      .catch((error: unknown) => setProblem(error instanceof ApiError ? error.problem : undefined))
      .finally(() => setBusy(false))
  }
  return (
    <>
      <Button type="button" variant="secondary" disabled={busy} onClick={print}>
        {t('sales.printTicket')}
      </Button>
      {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
    </>
  )
}
