import { describe, expect, it } from 'vitest'
import { vatApplies } from './vat'

describe('vatApplies (ADR-181)', () => {
  it('is false when every line is at 0 % or there is no line', () => {
    expect(vatApplies([])).toBe(false)
    expect(vatApplies([{ vatRate: '0.00' }, { vatRate: '0' }])).toBe(false)
  })

  it('is true as soon as one line is taxed', () => {
    expect(vatApplies([{ vatRate: '0.00' }, { vatRate: '9.00' }])).toBe(true)
  })
})
