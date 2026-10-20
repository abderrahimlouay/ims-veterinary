import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink, Outlet } from 'react-router'
import { LanguageSelect } from '../../components/LanguageSelect'

/** Back-office layout (frontend.md §3): top bar, side navigation, content. Logical CSS only (ADR-033). */
export function AppLayout() {
  const { t } = useTranslation()

  useEffect(() => {
    document.title = t('app.name')
  }, [t])

  return (
    <div className="min-h-screen flex flex-col">
      <header className="flex items-center gap-4 h-14 px-4 bg-surface border-b border-line">
        <span className="font-semibold text-brand">{t('app.name')}</span>
        <div className="ms-auto">
          <LanguageSelect />
        </div>
      </header>
      <div className="flex flex-1">
        <nav aria-label={t('nav.label')} className="w-56 shrink-0 p-3 bg-surface border-e border-line">
          <NavLink
            to="/"
            end
            className={({ isActive }) =>
              `block px-3 py-2 rounded-ctl text-sm font-medium ${isActive ? 'bg-brand/10 text-brand' : 'text-ink hover:bg-bg'}`
            }
          >
            {t('nav.home')}
          </NavLink>
        </nav>
        <main className="flex-1 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
