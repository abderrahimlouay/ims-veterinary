import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

export function NotFoundPage() {
  const { t } = useTranslation()
  return (
    <section className="max-w-prose">
      <h1 className="text-2xl font-semibold mb-2">{t('notFound.title')}</h1>
      <p className="text-mute mb-4">{t('notFound.body')}</p>
      <Link to="/" className="text-brand font-medium underline underline-offset-4">
        {t('notFound.back')}
      </Link>
    </section>
  )
}
