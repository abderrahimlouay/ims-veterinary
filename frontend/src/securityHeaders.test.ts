import { describe, expect, it } from 'vitest'
import { SECURITY_HEADERS } from './securityHeaders'

/** ADR-157, ADR-166: the policy the application and the checked preview send stays strict. */
describe('security headers', () => {
  it('keep the policy strict', () => {
    const csp = SECURITY_HEADERS['Content-Security-Policy']
    expect(csp).not.toContain('unsafe')
    expect(csp).toContain("script-src 'self'")
    expect(csp).toContain("frame-ancestors 'none'")
    expect(csp).toContain("object-src 'none'")
    expect(SECURITY_HEADERS['X-Frame-Options']).toBe('DENY')
    expect(SECURITY_HEADERS['X-Content-Type-Options']).toBe('nosniff')
  })
})
