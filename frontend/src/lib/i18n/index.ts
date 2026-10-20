import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import ar from './locales/ar.json'
import en from './locales/en.json'
import fr from './locales/fr.json'

/** UI languages of ADR-033; French is the default. */
export const LANGUAGES = ['fr', 'ar', 'en'] as const
export type Language = (typeof LANGUAGES)[number]

const RIGHT_TO_LEFT: ReadonlySet<string> = new Set(['ar'])
const STORAGE_KEY = 'ims.language'

function storedLanguage(): Language {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return LANGUAGES.find((l) => l === value) ?? 'fr'
  } catch {
    return 'fr'
  }
}

function applyToDocument(language: string): void {
  document.documentElement.lang = language
  document.documentElement.dir = RIGHT_TO_LEFT.has(language) ? 'rtl' : 'ltr'
  try {
    localStorage.setItem(STORAGE_KEY, language)
  } catch {
    // Preference not persisted (private mode); the language still applies to this page.
  }
}

export const i18n = i18next.createInstance()

i18n.on('languageChanged', applyToDocument)

void i18n.use(initReactI18next).init({
  resources: { fr: { translation: fr }, ar: { translation: ar }, en: { translation: en } },
  lng: storedLanguage(),
  fallbackLng: 'fr',
  supportedLngs: LANGUAGES,
  interpolation: { escapeValue: false },
  initAsync: false,
})
