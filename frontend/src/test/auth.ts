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

export const PUBLIC_SETTINGS = {
  expiryAlertDays: [60, 30, 15],
  defaultVatRate: '9.00',
  currency: 'DZD',
  locale: 'fr-DZ',
  timeZone: 'Africa/Algiers',
}

/** `GET /api/auth/me` answers with this user, or 401 when `null`; public settings are served too. */
export function serveMe(user: CurrentUser | null) {
  server.use(
    http.get('/api/auth/me', () => (user ? HttpResponse.json(user) : problem(401, 'AUTH_REQUIRED'))),
    http.get('/api/settings/public', () => HttpResponse.json(PUBLIC_SETTINGS)),
  )
}

export const ADMIN_PERMISSIONS = ['user.read', 'user.manage', 'role.manage', 'settings.manage', 'audit.read']

export function anAdmin(): CurrentUser {
  return aUser({
    user: { id: 1, username: 'admin', fullName: 'Amina Benali', email: null, role: 'Administrator' },
    permissions: ADMIN_PERMISSIONS,
  })
}
