import type { ReactNode } from 'react'

type Tone = 'danger' | 'ok' | 'warn'

const TONES: Record<Tone, string> = {
  danger: 'border-danger/40 bg-danger/10 text-danger',
  ok: 'border-ok/40 bg-ok/10 text-ok',
  warn: 'border-warn/40 bg-warn/10 text-warn',
}

/** Errors are announced at once (`role="alert"`); confirmations politely (`role="status"`). */
export function Alert({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <div role={tone === 'danger' ? 'alert' : 'status'} className={`px-3 py-2 rounded-ctl border text-sm ${TONES[tone]}`}>
      {children}
    </div>
  )
}
