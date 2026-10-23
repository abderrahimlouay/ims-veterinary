import { useTranslation } from 'react-i18next'
import type { CountStatus } from './countApi'

const BADGE = 'inline-flex items-center px-2 py-0.5 rounded-ctl text-xs font-medium'

/** Tone tints whose contrast is checked in both themes (contrast.test.ts, ADR-069). */
const TONES: Record<CountStatus, string> = {
  open: 'bg-brand/10 text-brand',
  closed: 'bg-ok/10 text-ok',
  cancelled: 'border border-line text-mute',
}

export function CountStatusBadge({ status }: { status: CountStatus }) {
  const { t } = useTranslation()
  return <span className={`${BADGE} ${TONES[status]}`}>{t(`counts.status.${status}`)}</span>
}
