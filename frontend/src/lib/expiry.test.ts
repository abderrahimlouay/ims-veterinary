import { describe, expect, it } from 'vitest'
import { businessToday, parseExpiry } from './expiry'

describe('expiry input (ADR-055)', () => {
  it('reads a full date in either form', () => {
    expect(parseExpiry('2027-05-31')).toBe('2027-05-31')
    expect(parseExpiry(' 31/05/2027 ')).toBe('2027-05-31')
    expect(parseExpiry('1/2/2028')).toBe('2028-02-01')
  })

  it('reads a month as its last day', () => {
    expect(parseExpiry('05/2027')).toBe('2027-05-31')
    expect(parseExpiry('2/2028')).toBe('2028-02-29')
    expect(parseExpiry('02/2027')).toBe('2027-02-28')
  })

  it('refuses what is not a real date', () => {
    expect(parseExpiry('')).toBeNull()
    expect(parseExpiry('31/02/2027')).toBeNull()
    expect(parseExpiry('13/2027')).toBeNull()
    expect(parseExpiry('2027-5-31')).toBeNull()
  })

  it('gives today in the business time zone', () => {
    expect(businessToday(new Date('2026-10-20T23:30:00Z'))).toBe('2026-10-21')
  })
})
