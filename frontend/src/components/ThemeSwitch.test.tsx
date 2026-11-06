import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { I18nextProvider } from 'react-i18next'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../lib/i18n'
import { THEME_STORAGE_KEY, reloadTheme } from '../lib/theme'
import { ThemeSwitch } from './ThemeSwitch'

function renderSwitch(count = 1) {
  return render(
    <I18nextProvider i18n={i18n}>
      {Array.from({ length: count }, (_, i) => (
        <ThemeSwitch key={i} />
      ))}
    </I18nextProvider>,
  )
}

const radio = (name: string) => screen.getByRole('radio', { name }) as HTMLInputElement
const html = () => document.documentElement

beforeEach(() => {
  localStorage.clear()
  reloadTheme()
})

afterEach(async () => {
  vi.restoreAllMocks()
  localStorage.clear()
  reloadTheme()
  await act(() => i18n.changeLanguage('fr'))
})

describe('ThemeSwitch (ADR-163)', () => {
  it('is a labelled radio group of three choices, System by default following the operating system', () => {
    renderSwitch()
    expect(screen.getByRole('radiogroup', { name: 'Thème' })).toBeInTheDocument()
    expect(screen.getAllByRole('radio').map((r) => r.getAttribute('value'))).toEqual(['system', 'light', 'dark'])
    expect(radio('Système').checked).toBe(true)
    expect(html().hasAttribute('data-theme')).toBe(false)
  })

  it('forces dark or light on <html> and remembers it; System removes both', async () => {
    const user = userEvent.setup()
    renderSwitch()
    await user.click(radio('Sombre'))
    expect(radio('Sombre').checked).toBe(true)
    expect(html().getAttribute('data-theme')).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')

    await user.click(radio('Clair'))
    expect(html().getAttribute('data-theme')).toBe('light')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')

    await user.click(radio('Système'))
    expect(html().hasAttribute('data-theme')).toBe(false)
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull()
  })

  it('moves the choice with the arrow keys', async () => {
    const user = userEvent.setup()
    renderSwitch()
    await user.tab()
    expect(radio('Système')).toHaveFocus()
    await user.keyboard('{ArrowRight}')
    expect(radio('Clair').checked).toBe(true)
    expect(html().getAttribute('data-theme')).toBe('light')
    await user.keyboard('{ArrowRight}')
    expect(radio('Sombre').checked).toBe(true)
    expect(html().getAttribute('data-theme')).toBe('dark')
  })

  it('keeps the choice across a remount, as after a reload', async () => {
    const user = userEvent.setup()
    const { unmount } = renderSwitch()
    await user.click(radio('Sombre'))
    unmount()
    html().removeAttribute('data-theme')
    reloadTheme()
    renderSwitch()
    expect(radio('Sombre').checked).toBe(true)
    expect(html().getAttribute('data-theme')).toBe('dark')
  })

  it('shows the same choice in every switch on the page (app header and POS header)', async () => {
    const user = userEvent.setup()
    renderSwitch(2)
    await user.click(screen.getAllByRole('radio', { name: 'Sombre' })[0])
    expect(screen.getAllByRole('radio', { name: 'Sombre' }).map((r) => (r as HTMLInputElement).checked)).toEqual([
      true,
      true,
    ])
  })

  it('still applies the theme when storage is blocked, and falls back to System on reload', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    const user = userEvent.setup()
    renderSwitch()
    await user.click(radio('Sombre'))
    expect(html().getAttribute('data-theme')).toBe('dark')
    reloadTheme()
    expect(html().hasAttribute('data-theme')).toBe(false)
  })

  it('ignores an unknown stored value', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'purple')
    reloadTheme()
    renderSwitch()
    expect(radio('Système').checked).toBe(true)
    expect(html().hasAttribute('data-theme')).toBe(false)
  })

  it('is translated, Arabic included', async () => {
    renderSwitch()
    await act(() => i18n.changeLanguage('ar'))
    expect(screen.getByRole('radiogroup', { name: 'المظهر' })).toBeInTheDocument()
    expect(screen.getAllByRole('radio').map((r) => r.closest('label')?.textContent)).toEqual([
      'النظام',
      'فاتح',
      'داكن',
    ])
  })

  it('has no axe violation', async () => {
    const { container } = renderSwitch()
    const result = await axe.run(container, { runOnly: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] })
    expect(result.violations).toEqual([])
  })
})
