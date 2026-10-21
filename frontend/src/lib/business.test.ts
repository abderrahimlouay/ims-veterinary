import { afterEach, describe, expect, it } from 'vitest'
import { applyBusinessSettings } from './business'
import { formatDateTime } from './format'

describe('business settings (ADR-044)', () => {
  afterEach(() => applyBusinessSettings({ currency: 'DZD', locale: 'fr-DZ', timeZone: 'Africa/Algiers' }))

  it('formatting follows the settings received from the server', () => {
    expect(formatDateTime('2026-10-20T23:30:00Z')).toMatch(/^21\/10\/2026/)

    applyBusinessSettings({ currency: 'DZD', locale: 'fr-DZ', timeZone: 'UTC' })

    expect(formatDateTime('2026-10-20T23:30:00Z')).toMatch(/^20\/10\/2026/)
  })
})
