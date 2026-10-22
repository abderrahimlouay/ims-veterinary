import { useTranslation } from 'react-i18next'
import type { AlertLevel, LotStatus } from './lotApi'

const BADGE = 'inline-flex items-center px-2 py-0.5 rounded-ctl text-xs font-medium'

/** Tone tints whose contrast is checked in both themes (contrast.test.ts, ADR-069). */
const STATUS_TONES: Record<LotStatus, string> = {
  active: 'bg-ok/10 text-ok',
  blocked: 'bg-warn/10 text-warn',
  recalled: 'bg-danger/10 text-danger',
  expired: 'bg-danger/10 text-danger',
  depleted: 'border border-line text-mute',
}

const LEVEL_TONES: Record<AlertLevel, string> = {
  expired: 'bg-danger/10 text-danger',
  critical: 'bg-danger/10 text-danger',
  warning: 'bg-warn/10 text-warn',
  notice: 'bg-brand/10 text-brand',
}

/** The effective status returned by the API (ADR-059). */
export function LotStatusBadge({ status }: { status: LotStatus }) {
  const { t } = useTranslation()
  return <span className={`${BADGE} ${STATUS_TONES[status]}`}>{t(`lots.status.${status}`)}</span>
}

/** The expiry level returned by the API, never computed in the browser (ADR-056); nothing when `null`. */
export function AlertBadge({ level, label }: { level: AlertLevel | null; label?: string }) {
  const { t } = useTranslation()
  if (level === null) {
    return null
  }
  return <span className={`${BADGE} ${LEVEL_TONES[level]}`}>{label ?? t(`lots.level.${level}`)}</span>
}
