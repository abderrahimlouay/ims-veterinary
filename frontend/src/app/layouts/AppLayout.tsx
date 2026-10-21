import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink, Outlet, useNavigate } from 'react-router'
import { Button } from '../../components/Button'
import { LanguageSelect } from '../../components/LanguageSelect'
import { useCurrentUser, useLogout } from '../../features/auth'
import { NAV_ITEMS, visibleNavItems } from '../navigation'

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `block px-3 py-2 rounded-ctl text-sm font-medium ${isActive ? 'bg-brand/10 text-brand' : 'text-ink hover:bg-bg'}`

/** Back-office layout (frontend.md §3): top bar with the user, permission-filtered navigation, content. */
export function AppLayout() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { data: current } = useCurrentUser()
  const logout = useLogout()

  useEffect(() => {
    document.title = t('app.name')
  }, [t])

  return (
    <div className="min-h-screen flex flex-col">
      <header className="flex items-center gap-4 h-14 px-4 bg-surface border-b border-line">
        <span className="font-semibold text-brand">{t('app.name')}</span>
        <div className="ms-auto flex items-center gap-4">
          <LanguageSelect />
          {current && (
            <span className="text-sm">
              <span className="font-medium">{current.user.fullName}</span>{' '}
              <span className="text-mute">({current.user.role})</span>
            </span>
          )}
          <Button
            variant="secondary"
            disabled={logout.isPending}
            onClick={() => logout.mutate(undefined, { onSettled: () => void navigate('/login', { replace: true }) })}
          >
            {t('auth.logout')}
          </Button>
        </div>
      </header>
      <div className="flex flex-1">
        <nav aria-label={t('nav.label')} className="w-56 shrink-0 p-3 bg-surface border-e border-line flex flex-col gap-1">
          {visibleNavItems(NAV_ITEMS, current?.permissions ?? []).map((item) => (
            <NavLink key={item.to} to={item.to} end={item.to === '/'} className={linkClass}>
              {t(item.labelKey)}
            </NavLink>
          ))}
          <NavLink to="/account" className={linkClass}>
            {t('nav.account')}
          </NavLink>
        </nav>
        <main className="flex-1 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
