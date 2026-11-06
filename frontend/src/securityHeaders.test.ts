import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { SECURITY_HEADERS } from './securityHeaders'

/** ADR-157: the proxy sends exactly the headers the end-to-end journeys run under. */
describe('security headers', () => {
  const proxy = readFileSync(resolve(__dirname, '../../deploy/nginx/ims-security-headers.conf'), 'utf8')
  const sent = new Map(
    [...proxy.matchAll(/^add_header ([\w-]+) "(.*)" always;$/gm)].map((m) => [m[1], m[2]] as const),
  )

  it('are the same in the reverse proxy and in the checked preview', () => {
    for (const [name, value] of Object.entries(SECURITY_HEADERS)) {
      expect(sent.get(name), name).toBe(value)
    }
  })

  it('add HSTS in the proxy and keep the policy strict', () => {
    expect(sent.get('Strict-Transport-Security')).toBe('max-age=31536000')
    const csp = SECURITY_HEADERS['Content-Security-Policy']
    expect(csp).not.toContain('unsafe')
    expect(csp).toContain("frame-ancestors 'none'")
    expect(csp).toContain("object-src 'none'")
  })
})
