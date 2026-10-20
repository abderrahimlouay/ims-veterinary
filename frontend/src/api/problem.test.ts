import { describe, expect, it } from 'vitest'
import { normaliseProblem, networkProblem } from './problem'

describe('normaliseProblem', () => {
  it('keeps the fields of a Problem Details body', () => {
    const problem = normaliseProblem(422, new Headers(), {
      type: 'urn:ims:problem:stock-insufficient',
      title: 'Stock insufficient',
      status: 422,
      code: 'STOCK_INSUFFICIENT',
      detail: 'Not enough stock.',
      instance: '/api/sales/1/confirm',
      traceId: 'abc',
      errors: [],
      context: { lotId: 88, availableBase: '9.000' },
    })

    expect(problem).toEqual({
      status: 422,
      code: 'STOCK_INSUFFICIENT',
      title: 'Stock insufficient',
      detail: 'Not enough stock.',
      traceId: 'abc',
      errors: [],
      context: { lotId: 88, availableBase: '9.000' },
    })
  })

  it('keeps field errors for VALIDATION_FAILED', () => {
    const problem = normaliseProblem(400, new Headers(), {
      code: 'VALIDATION_FAILED',
      errors: [{ field: 'lines[0].lotNumber', code: 'REQUIRED', message: 'must not be blank' }],
    })

    expect(problem.errors).toEqual([
      { field: 'lines[0].lotNumber', code: 'REQUIRED', message: 'must not be blank' },
    ])
  })

  it('derives a code from the status when the body is not Problem Details', () => {
    const headers = new Headers({ 'X-Trace-Id': 'from-header' })

    expect(normaliseProblem(502, headers, '<html>Bad gateway</html>')).toMatchObject({
      status: 502,
      code: 'INTERNAL_ERROR',
      traceId: 'from-header',
      errors: [],
      context: {},
    })
    expect(normaliseProblem(404, new Headers(), undefined).code).toBe('NOT_FOUND')
    expect(normaliseProblem(401, new Headers(), undefined).code).toBe('AUTH_REQUIRED')
    expect(normaliseProblem(403, new Headers(), undefined).code).toBe('ACCESS_DENIED')
    expect(normaliseProblem(429, new Headers(), undefined).code).toBe('RATE_LIMITED')
  })

  it('represents an unreachable server as NETWORK_ERROR with status 0', () => {
    expect(networkProblem()).toMatchObject({ status: 0, code: 'NETWORK_ERROR', errors: [], context: {} })
  })
})
