import { describe, expect, it } from 'vitest'
import { thresholdsInconsistent } from './thresholds'

describe('threshold warning (ADR-092)', () => {
  it('warns when the max stock is at or below a reorder point above zero', () => {
    expect(thresholdsInconsistent('10', '10.000')).toBe(true)
    expect(thresholdsInconsistent('8', '10')).toBe(true)
    expect(thresholdsInconsistent('0', '0.5')).toBe(true)
  })

  it('stays silent otherwise', () => {
    expect(thresholdsInconsistent('50', '10')).toBe(false)
    expect(thresholdsInconsistent('', '10')).toBe(false)
    expect(thresholdsInconsistent('5', '0')).toBe(false)
    expect(thresholdsInconsistent('5', '0.000')).toBe(false)
    expect(thresholdsInconsistent('abc', '10')).toBe(false)
  })
})
