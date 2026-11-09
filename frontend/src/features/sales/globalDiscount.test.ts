import { describe, expect, it } from 'vitest'
import { deduction, editableValue } from './globalDiscount'

describe('global discount display (ADR-175)', () => {
  it('signs a deduction only when something was taken off', () => {
    expect(deduction('2000.00')).toMatch(/^-2\s000,00/)
    expect(deduction('0.00')).toMatch(/^0,00/)
  })

  it('shows the value as typed, without trailing zeros', () => {
    expect(editableValue('10.00')).toBe('10')
    expect(editableValue('2.50')).toBe('2.5')
    expect(editableValue('3150.00')).toBe('3150')
    expect(editableValue('100')).toBe('100')
  })
})
