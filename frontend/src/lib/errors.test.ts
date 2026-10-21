import { afterEach, describe, expect, it } from 'vitest'
import type { Problem } from '../api/problem'
import { i18n } from './i18n'
import { problemMessage } from './errors'

const base: Problem = { status: 400, code: 'X', errors: [], context: {} }

describe('problemMessage (frontend.md §9)', () => {
  afterEach(async () => {
    await i18n.changeLanguage('fr')
  })

  it('chooses the text by code, not by the server detail', () => {
    expect(problemMessage({ ...base, code: 'AUTH_INVALID_CREDENTIALS', detail: 'server text' }, i18n.t)).toBe(
      "Nom d'utilisateur ou mot de passe incorrect.",
    )
  })

  it('turns retryAfterSeconds into whole minutes', () => {
    expect(problemMessage({ ...base, code: 'RATE_LIMITED', context: { retryAfterSeconds: 61 } }, i18n.t)).toBe(
      'Trop de tentatives. Réessayez dans 2 min.',
    )
  })

  it('shows the trace id of unexpected failures and falls back to the detail for unknown codes', () => {
    expect(problemMessage({ ...base, code: 'INTERNAL_ERROR', traceId: 'abc' }, i18n.t)).toContain('abc')
    expect(problemMessage({ ...base, code: 'SOMETHING_NEW', detail: 'Server says.' }, i18n.t)).toBe('Server says.')
  })
})
