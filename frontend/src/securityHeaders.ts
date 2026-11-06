import headers from './securityHeaders.json' with { type: 'json' }

/**
 * The security headers of the screens (ADR-157, ADR-166, security.md §11). `securityHeaders.json` is the single
 * source: the application sends it with every screen in production (`FrontendHeaders`, the backend build copies the
 * file), and `vite preview` sends it, so the end-to-end journeys run under the same Content-Security-Policy (J13).
 * `frame-src blob:` is the hidden print frame (ADR-139); the API sends its own stricter policy.
 */
export const SECURITY_HEADERS: Readonly<Record<string, string>> = headers
