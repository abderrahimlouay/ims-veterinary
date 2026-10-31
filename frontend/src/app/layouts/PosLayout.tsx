import { useTranslation } from 'react-i18next'
import { Link, Outlet } from 'react-router'

/** `PosLayout` (frontend.md §2): the full-screen counter, with no sidebar and one way back. */
export function PosLayout() {
  const { t } = useTranslation()
  return (
    <div className="min-h-screen bg-bg text-ink flex flex-col">
      <header className="px-4 py-2 border-b border-line bg-surface text-sm">
        <Link to="/" className="underline">
          {t('pos.back')}
        </Link>
      </header>
      <main className="flex-1 p-4">
        <Outlet />
      </main>
    </div>
  )
}
