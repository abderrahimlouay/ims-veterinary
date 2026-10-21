import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { Outlet } from 'react-router'
import { LanguageSelect } from '../../components/LanguageSelect'

/** Centred card for `/login` (frontend.md §3). */
export function AuthLayout() {
  const { t } = useTranslation()

  useEffect(() => {
    document.title = t('app.name')
  }, [t])

  return (
    <div className="min-h-screen flex flex-col items-center justify-center gap-6 p-4 bg-bg">
      <p className="text-lg font-semibold text-brand">{t('app.name')}</p>
      <main className="w-full max-w-sm p-6 bg-surface border border-line rounded-ctl shadow-sm">
        <Outlet />
      </main>
      <LanguageSelect />
    </div>
  )
}
