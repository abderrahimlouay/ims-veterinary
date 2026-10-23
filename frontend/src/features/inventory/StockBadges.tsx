import { useTranslation } from 'react-i18next'
import type { StockStatus } from './stockApi'

const BADGE = 'inline-flex items-center px-2 py-0.5 rounded-ctl text-xs font-medium'

/** Tone tints whose contrast is checked in both themes (contrast.test.ts, ADR-069). */
const TONES: Record<StockStatus, string> = {
  in_stock: 'bg-ok/10 text-ok',
  low: 'bg-warn/10 text-warn',
  out: 'bg-danger/10 text-danger',
}

/** The status computed by the server (ADR-080), never in the browser. */
export function StockStatusBadge({ status }: { status: StockStatus }) {
  const { t } = useTranslation()
  return <span className={`${BADGE} ${TONES[status]}`}>{t(`stock.status.${status}`)}</span>
}

export function BelowReorderBadge() {
  const { t } = useTranslation()
  return <span className={`${BADGE} border border-line text-ink`}>{t('stock.belowReorder')}</span>
}
