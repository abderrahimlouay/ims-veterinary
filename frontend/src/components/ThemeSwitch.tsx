import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { THEMES, setTheme, useTheme } from '../lib/theme'

/**
 * System / Light / Dark (ADR-163): native radios drawn as a segmented group, so arrow keys move the choice and
 * screen readers announce a labelled radio group. Next to the language selector in the app, POS and login headers.
 */
export function ThemeSwitch() {
  const { t } = useTranslation()
  const theme = useTheme()
  const id = useId()
  return (
    <div className="flex items-center gap-2">
      <span id={`${id}-label`} className="text-sm text-mute">
        {t('theme.label')}
      </span>
      <div role="radiogroup" aria-labelledby={`${id}-label`} className="inline-flex rounded-ctl border border-control">
        {THEMES.map((choice, index) => (
          <label key={choice} className="relative">
            <input
              type="radio"
              name={`${id}-theme`}
              value={choice}
              checked={theme === choice}
              onChange={() => setTheme(choice)}
              className="peer sr-only"
            />
            <span
              className={`block h-9 px-3 leading-9 text-sm cursor-pointer select-none ${theme === choice ? 'bg-brand text-brand-ink font-medium' : 'bg-surface text-ink hover:bg-bg'} peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-brand ${index === 0 ? 'rounded-s-ctl' : 'border-s border-control'} ${index === THEMES.length - 1 ? 'rounded-e-ctl' : ''}`}
            >
              {t(`theme.${choice}`)}
            </span>
          </label>
        ))}
      </div>
    </div>
  )
}
