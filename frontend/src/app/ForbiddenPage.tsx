import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

/** Shown in place when a permission is missing (frontend.md §3): never a blank page or a redirect loop. */
export function ForbiddenPage() {
  const { t } = useTranslation()
  return (
    <section className="max-w-prose">
      <h1 className="text-2xl font-semibold mb-2">{t('forbidden.title')}</h1>
      <p className="text-mute mb-4">{t('forbidden.body')}</p>
      <Link to="/" className="text-brand font-medium underline underline-offset-4">
        {t('notFound.back')}
      </Link>
    </section>
  )
}
