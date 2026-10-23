import { describe, expect, it } from 'vitest'
import { isNegativeDecimal, subtractDecimal } from './decimal'

describe('decimal previews (ADR-021: no floating point)', () => {
  it('subtracts exactly at three decimals', () => {
    expect(subtractDecimal('120.000', '2.5')).toBe('117.500')
    expect(subtractDecimal('0.3', '0.1')).toBe('0.200')
    expect(subtractDecimal('1', '1')).toBe('0.000')
    expect(subtractDecimal('2', '3.25')).toBe('-1.250')
    expect(subtractDecimal('10', '0,5')).toBe('9.500')
  })

  it('gives nothing for text that is not a decimal of that scale', () => {
    expect(subtractDecimal('10', 'abc')).toBeNull()
    expect(subtractDecimal('10', '')).toBeNull()
    expect(subtractDecimal('10', '1.0001')).toBeNull()
  })

  it('tells a negative value', () => {
    expect(isNegativeDecimal('-1.250')).toBe(true)
    expect(isNegativeDecimal('-0.000')).toBe(false)
    expect(isNegativeDecimal('0.000')).toBe(false)
  })
})
