import { describe, expect, it } from 'vitest'
import { formatDateTime, formatMoney, formatQuantity } from './format'

describe('format (ADR-033: DZD, fr-DZ, Africa/Algiers)', () => {
  it('formats money strings as Algerian dinars with two decimals', () => {
    expect(formatMoney('1234.5')).toMatch(/^1\s234,50\sDA$/)
  })

  it('formats large amounts exactly, without binary floating point', () => {
    expect(formatMoney('12345678901234567.89')).toMatch(/,89\sDA$/)
  })

  it('formats quantities with three decimals', () => {
    expect(formatQuantity('12.5')).toBe('12,500')
  })

  it('shows instants in the business time zone, not UTC', () => {
    expect(formatDateTime('2026-12-31T23:30:00Z')).toContain('01/01/2027')
  })

  it('uses a 24-hour clock (ADR-033)', () => {
    expect(formatDateTime('2026-12-31T23:30:00Z')).toBe('01/01/2027 00:30')
    expect(formatDateTime('2026-12-31T13:05:00Z')).toBe('31/12/2026 14:05')
  })
})
