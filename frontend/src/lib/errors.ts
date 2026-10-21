import type { TFunction } from 'i18next'
import type { Problem } from '../api/problem'

/**
 * The user-facing text of a failed request (frontend.md §9): chosen by `code`, localised; the server's
 * `detail` is only a fallback. Support needs the trace id of unexpected failures.
 */
export function problemMessage(problem: Problem, t: TFunction): string {
  const retryAfter = problem.context.retryAfterSeconds
  const minutes = typeof retryAfter === 'number' ? Math.max(1, Math.ceil(retryAfter / 60)) : undefined
  const key = `errors.${problem.code}`
  if (t(key, { defaultValue: '' }) !== '') {
    return t(key, { minutes, traceId: problem.traceId ?? '—' })
  }
  return problem.detail ?? t('errors.UNKNOWN', { traceId: problem.traceId ?? '—' })
}
