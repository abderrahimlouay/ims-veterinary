import { useTranslation } from 'react-i18next'
import { Suspense } from 'react'
import { Link, Outlet } from 'react-router'
import { ThemeSwitch } from '../../components/ThemeSwitch'
import { PageLoading } from './AppLayout'

/** `PosLayout` (frontend.md §2): the full-screen counter, with no sidebar and one way back. */
export function PosLayout() {
  const { t } = useTranslation()
  return (
    <div className="min-h-screen bg-bg text-ink flex flex-col">
      <header className="flex items-center gap-4 px-4 py-2 border-b border-line bg-surface text-sm">
        <Link to="/" className="underline">
          {t('pos.back')}
        </Link>
        <div className="ms-auto">
          <ThemeSwitch />
        </div>
      </header>
      <main className="flex-1 p-4">
        <Suspense fallback={<PageLoading />}>
          <Outlet />
        </Suspense>
      </main>
    </div>
  )
}
