import { useSyncExternalStore } from 'react'

/**
 * The theme chosen in this browser (ADR-069, ADR-163): `system` follows the operating system, `light` and `dark`
 * force one. `light`/`dark` set `data-theme` on `<html>` (tokens.css), `system` removes it. The choice is kept in
 * `localStorage`; every access is guarded, so blocked storage only means the choice is not remembered.
 * `public/theme-init.js` applies the same key before first paint (ADR-162).
 */
export const THEMES = ['system', 'light', 'dark'] as const
export type Theme = (typeof THEMES)[number]

export const THEME_STORAGE_KEY = 'ims.theme'

function isTheme(value: unknown): value is Theme {
  return THEMES.some((t) => t === value)
}

function storedTheme(): Theme {
  try {
    const value = localStorage.getItem(THEME_STORAGE_KEY)
    return isTheme(value) ? value : 'system'
  } catch {
    return 'system'
  }
}

function applyToDocument(theme: Theme): void {
  const root = document.documentElement
  if (theme === 'system') {
    root.removeAttribute('data-theme')
  } else {
    root.setAttribute('data-theme', theme)
  }
}

let current: Theme = storedTheme()
const listeners = new Set<() => void>()

function notify(): void {
  listeners.forEach((listener) => listener())
}

export function getTheme(): Theme {
  return current
}

export function setTheme(theme: Theme): void {
  current = theme
  applyToDocument(theme)
  try {
    if (theme === 'system') {
      localStorage.removeItem(THEME_STORAGE_KEY)
    } else {
      localStorage.setItem(THEME_STORAGE_KEY, theme)
    }
  } catch {
    // Not remembered (storage blocked); the theme still applies to this page.
  }
  notify()
}

/** Reads the stored choice again, as on a new page load (tests, and a change made in another tab). */
export function reloadTheme(): void {
  current = storedTheme()
  applyToDocument(current)
  notify()
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.key === THEME_STORAGE_KEY || event.key === null) {
      reloadTheme()
    }
  })
}

/** The current theme; every switch on the page shows the same choice. */
export function useTheme(): Theme {
  return useSyncExternalStore(subscribe, getTheme, getTheme)
}
