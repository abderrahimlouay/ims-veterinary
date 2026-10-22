import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { LANGUAGES } from '../lib/i18n'

export function LanguageSelect() {
  const { t, i18n } = useTranslation()
  const id = useId()
  return (
    <div className="flex items-center gap-2">
      <label htmlFor={id} className="text-sm text-mute">
        {t('language.label')}
      </label>
      <select
        id={id}
        value={i18n.language}
        onChange={(e) => void i18n.changeLanguage(e.target.value)}
        className="h-9 px-2 rounded-ctl bg-surface border border-control text-sm focus:outline-2 focus:outline-brand"
      >
        {LANGUAGES.map((lng) => (
          <option key={lng} value={lng} lang={lng}>
            {t(`language.${lng}`)}
          </option>
        ))}
      </select>
    </div>
  )
}
