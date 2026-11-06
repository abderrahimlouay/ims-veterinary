/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { THEME_STORAGE_KEY } from './theme'

/** `public/theme-init.js` (ADR-162): applies the saved theme before first paint, with the key of `theme.ts`. */
const script = readFileSync(resolve(process.cwd(), 'public/theme-init.js'), 'utf8')
const run = () => new Function(script)()
const html = () => document.documentElement

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  html().removeAttribute('data-theme')
})

describe('theme-init.js', () => {
  it('reads the same storage key as the switch', () => {
    expect(script).toContain(`'${THEME_STORAGE_KEY}'`)
  })

  it.each(['light', 'dark'])('applies a saved %s theme', (theme) => {
    localStorage.setItem(THEME_STORAGE_KEY, theme)
    run()
    expect(html().getAttribute('data-theme')).toBe(theme)
  })

  it('leaves the operating-system setting without a choice, with an unknown value, or with blocked storage', () => {
    run()
    expect(html().hasAttribute('data-theme')).toBe(false)
    localStorage.setItem(THEME_STORAGE_KEY, 'system')
    run()
    expect(html().hasAttribute('data-theme')).toBe(false)
    localStorage.setItem(THEME_STORAGE_KEY, '"><script>')
    run()
    expect(html().hasAttribute('data-theme')).toBe(false)
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    expect(run).not.toThrow()
    expect(html().hasAttribute('data-theme')).toBe(false)
  })

  it('is loaded as a blocking same-origin script before the application', () => {
    const page = readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')
    const init = page.indexOf('<script src="/theme-init.js"></script>')
    expect(init).toBeGreaterThan(-1)
    expect(init).toBeLessThan(page.indexOf('</head>'))
    expect(init).toBeLessThan(page.indexOf('type="module"'))
  })
})
