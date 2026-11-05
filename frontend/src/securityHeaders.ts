/**
 * The security headers of the frontend (ADR-157, security.md §11), served by the reverse proxy in production
 * (`deploy/nginx/ims-security-headers.conf`, kept identical by `securityHeaders.test.ts`) and by `vite preview`, so the
 * end-to-end journeys run under the same Content-Security-Policy. `frame-src blob:` is the hidden print frame
 * (ADR-139); the API sends its own stricter policy.
 */
export const SECURITY_HEADERS: Readonly<Record<string, string>> = {
  'Content-Security-Policy':
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; " +
    "frame-src blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'",
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
}
