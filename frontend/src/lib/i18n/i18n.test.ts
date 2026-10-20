import { afterEach, describe, expect, it } from 'vitest'
import ar from './locales/ar.json'
import en from './locales/en.json'
import fr from './locales/fr.json'
import { i18n } from './index'

function keys(obj: object, prefix = ''): string[] {
  return Object.entries(obj).flatMap(([k, v]) =>
    typeof v === 'object' && v !== null ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`],
  )
}

describe('i18n (ADR-033)', () => {
  afterEach(async () => {
    await i18n.changeLanguage('fr')
  })

  it('defaults to French, left-to-right', () => {
    expect(i18n.language).toBe('fr')
    expect(document.documentElement.lang).toBe('fr')
    expect(document.documentElement.dir).toBe('ltr')
  })

  it('switches the document to right-to-left for Arabic', async () => {
    await i18n.changeLanguage('ar')

    expect(document.documentElement.lang).toBe('ar')
    expect(document.documentElement.dir).toBe('rtl')
  })

  it('switches back to left-to-right for English', async () => {
    await i18n.changeLanguage('ar')
    await i18n.changeLanguage('en')

    expect(document.documentElement.dir).toBe('ltr')
  })

  it('has the same keys in every language', () => {
    expect(keys(ar).sort()).toEqual(keys(fr).sort())
    expect(keys(en).sort()).toEqual(keys(fr).sort())
  })
})
