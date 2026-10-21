import { http, HttpResponse } from 'msw'
import type { CurrentUser } from '../features/auth'
import { server } from './server'

export function aUser(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    user: { id: 7, username: 'amina', fullName: 'Amina Benali', email: null, role: 'Seller' },
    permissions: ['sale.read', 'sale.create'],
    mustChangePassword: false,
    ...overrides,
  }
}

export function problem(status: number, code: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { status, code, title: code, errors: [], context: {}, traceId: 'trace-1', ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

/** `GET /api/auth/me` answers with this user, or 401 when `null`. */
export function serveMe(user: CurrentUser | null) {
  server.use(http.get('/api/auth/me', () => (user ? HttpResponse.json(user) : problem(401, 'AUTH_REQUIRED'))))
}
