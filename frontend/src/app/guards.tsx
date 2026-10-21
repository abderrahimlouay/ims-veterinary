import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, useLocation } from 'react-router'
import { hasPermission, useCurrentUser } from '../features/auth'
import { ForbiddenPage } from './ForbiddenPage'

function Loading() {
  const { t } = useTranslation()
  return (
    <p role="status" className="p-6 text-mute">
      {t('app.loading')}
    </p>
  )
}

/**
 * Pages behind a session (BR-SEC-001 on the server; here only for navigation): anonymous users go to
 * `/login` with a return target; a user with a temporary password goes to `/account` (ADR-039).
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { data: current, isPending } = useCurrentUser()
  const location = useLocation()
  if (isPending) {
    return <Loading />
  }
  if (!current) {
    const target = `${location.pathname}${location.search}`
    return <Navigate to={target === '/' ? '/login' : `/login?redirect=${encodeURIComponent(target)}`} replace />
  }
  if (current.mustChangePassword && location.pathname !== '/account') {
    return <Navigate to="/account" replace />
  }
  return children
}

/** Cosmetic only (BR-SEC-002): the API refuses the same actions with ACCESS_DENIED. */
export function RequirePermission({ code, children }: { code: string; children: ReactNode }) {
  const { data: current } = useCurrentUser()
  return hasPermission(current, code) ? children : <ForbiddenPage />
}
