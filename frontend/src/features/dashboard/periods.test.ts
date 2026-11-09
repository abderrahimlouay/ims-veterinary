import { describe, expect, it } from 'vitest'
import { addDays, daysBetween, firstOfMonth, monthsBetween } from './periods'

describe('business-date arithmetic (ADR-176)', () => {
  it('moves across months and years', () => {
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28')
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01')
    expect(addDays('2026-11-09', -29)).toBe('2026-10-11')
  })

  it('gives the first day of a month before or after', () => {
    expect(firstOfMonth('2026-11-09')).toBe('2026-11-01')
    expect(firstOfMonth('2026-11-09', -11)).toBe('2025-12-01')
    expect(firstOfMonth('2026-12-15', 1)).toBe('2027-01-01')
  })

  it('lists every day and every month of a period', () => {
    expect(daysBetween('2026-10-30', '2026-11-02')).toEqual(['2026-10-30', '2026-10-31', '2026-11-01', '2026-11-02'])
    expect(daysBetween('2026-10-11', '2026-11-09')).toHaveLength(30)
    expect(monthsBetween('2025-12-01', '2026-11-09')).toHaveLength(12)
    expect(monthsBetween('2025-12-01', '2026-02-03')).toEqual(['2025-12', '2026-01', '2026-02'])
  })
})
