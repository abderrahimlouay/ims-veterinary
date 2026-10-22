import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { useState } from 'react'
import { I18nextProvider } from 'react-i18next'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../lib/i18n'
import { PasswordInput } from './PasswordInput'

const SECRET = 'Tall-cedar-river-2026' // gitleaks:allow

function Form({ onSubmit = () => {} }: { onSubmit?: () => void }) {
  return (
    <I18nextProvider i18n={i18n}>
      <form
        onSubmit={(event) => {
          event.preventDefault()
          onSubmit()
        }}
      >
        <PasswordInput label="Mot de passe" autoComplete="current-password" />
        <PasswordInput label="Nouveau mot de passe" autoComplete="new-password" />
        <button type="submit">Envoyer</button>
      </form>
      <button type="button">Ailleurs</button>
    </I18nextProvider>
  )
}

const field = (label: string) => screen.getByLabelText(label, { exact: true }) as HTMLInputElement
const toggles = () => screen.getAllByRole('button', { name: /mot de passe/ })

afterEach(() => act(() => void i18n.changeLanguage('fr')))

describe('PasswordInput', () => {
  it('is hidden by default and keeps its label and autocomplete', () => {
    render(<Form />)

    expect(field('Mot de passe')).toHaveAttribute('type', 'password')
    expect(field('Mot de passe')).toHaveAttribute('autocomplete', 'current-password')
    expect(field('Nouveau mot de passe')).toHaveAttribute('autocomplete', 'new-password')
    expect(toggles()).toHaveLength(2)
    for (const toggle of toggles()) {
      expect(toggle).toHaveAccessibleName('Afficher le mot de passe')
      expect(toggle).toHaveAttribute('aria-pressed', 'false')
      expect(toggle).toHaveAttribute('type', 'button')
    }
  })

  it('toggles its own field only, and the name follows the state', async () => {
    render(<Form />)
    await userEvent.type(field('Mot de passe'), SECRET)

    await userEvent.click(toggles()[0])

    expect(field('Mot de passe')).toHaveAttribute('type', 'text')
    expect(field('Mot de passe')).toHaveValue(SECRET)
    expect(field('Nouveau mot de passe')).toHaveAttribute('type', 'password')
    expect(toggles()[0]).toHaveAccessibleName('Masquer le mot de passe')
    expect(toggles()[0]).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(toggles()[0])

    expect(field('Mot de passe')).toHaveAttribute('type', 'password')
    expect(toggles()[0]).toHaveAccessibleName('Afficher le mot de passe')
  })

  it('never submits the form', async () => {
    const onSubmit = vi.fn()
    render(<Form onSubmit={onSubmit} />)

    await userEvent.click(toggles()[0])
    toggles()[0].focus()
    await userEvent.keyboard('{Enter}')

    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('hides the password again when the form is submitted, focus staying in the field', async () => {
    const onSubmit = vi.fn()
    render(<Form onSubmit={onSubmit} />)
    await userEvent.click(toggles()[0])
    await userEvent.click(field('Mot de passe'))

    await userEvent.keyboard('{Enter}')

    expect(onSubmit).toHaveBeenCalledOnce()
    expect(field('Mot de passe')).toHaveAttribute('type', 'password')
    expect(toggles()[0]).toHaveAttribute('aria-pressed', 'false')
  })

  it('hides the password again when focus leaves the field', async () => {
    render(<Form />)
    await userEvent.click(field('Mot de passe'))
    await userEvent.click(toggles()[0])
    await userEvent.click(field('Mot de passe'))
    expect(field('Mot de passe')).toHaveAttribute('type', 'text')

    await userEvent.click(screen.getByRole('button', { name: 'Ailleurs' }))

    expect(field('Mot de passe')).toHaveAttribute('type', 'password')
  })

  it('works with the keyboard: Tab reaches the eye, Space and Enter toggle it', async () => {
    render(<Form />)
    await userEvent.click(field('Mot de passe'))

    await userEvent.tab()
    expect(toggles()[0]).toHaveFocus()
    await userEvent.keyboard(' ')
    expect(field('Mot de passe')).toHaveAttribute('type', 'text')
    await userEvent.keyboard('{Enter}')
    expect(field('Mot de passe')).toHaveAttribute('type', 'password')
  })

  it('is hidden again when the page is left and shown anew', () => {
    function Page() {
      const [shown, setShown] = useState(true)
      return (
        <I18nextProvider i18n={i18n}>
          {shown && <PasswordInput label="Mot de passe" autoComplete="current-password" />}
          <button type="button" onClick={() => setShown((s) => !s)}>
            Naviguer
          </button>
        </I18nextProvider>
      )
    }
    render(<Page />)
    act(() => toggles()[0].click())
    expect(field('Mot de passe')).toHaveAttribute('type', 'text')

    act(() => screen.getByRole('button', { name: 'Naviguer' }).click())
    act(() => screen.getByRole('button', { name: 'Naviguer' }).click())

    expect(field('Mot de passe')).toHaveAttribute('type', 'password')
  })

  it.each([
    ['ar', 'إظهار كلمة المرور', 'إخفاء كلمة المرور'],
    ['en', 'Show password', 'Hide password'],
  ])('names the eye in %s', async (language, show, hide) => {
    await act(() => i18n.changeLanguage(language))
    render(<Form />)

    const eye = screen.getAllByRole('button', { name: show })[0]
    await userEvent.click(eye)
    expect(eye).toHaveAccessibleName(hide)
  })

  it('has no accessibility violations, hidden or shown', async () => {
    const { container } = render(<Form />)
    expect((await axe.run(container)).violations).toEqual([])

    await userEvent.click(toggles()[0])

    expect((await axe.run(container)).violations).toEqual([])
  })
})

describe('PasswordInput with a copy button', () => {
  function Copyable() {
    return (
      <I18nextProvider i18n={i18n}>
        <PasswordInput label="Mot de passe temporaire" readOnly value={SECRET} copyable />
      </I18nextProvider>
    )
  }

  it('copies the value without revealing it and announces the confirmation', async () => {
    const user = userEvent.setup()
    const write = vi.spyOn(navigator.clipboard, 'writeText')
    render(<Copyable />)

    const copy = screen.getByRole('button', { name: 'Copier le mot de passe' })
    expect(copy).toHaveAttribute('type', 'button')
    await user.click(copy)

    expect(write).toHaveBeenCalledWith(SECRET)
    expect(field('Mot de passe temporaire')).toHaveAttribute('type', 'password')
    expect(await screen.findByRole('status')).toHaveTextContent('Mot de passe copié.')
  })

  it('reports a clipboard failure without showing the value', async () => {
    const user = userEvent.setup()
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('denied'))
    render(<Copyable />)

    await user.click(screen.getByRole('button', { name: 'Copier le mot de passe' }))

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('Copie impossible')
    expect(status).not.toHaveTextContent(SECRET)
    expect(field('Mot de passe temporaire')).toHaveAttribute('type', 'password')
  })

  it('names the copy button in Arabic and English', async () => {
    await act(() => i18n.changeLanguage('ar'))
    const { unmount } = render(<Copyable />)
    expect(screen.getByRole('button', { name: 'نسخ كلمة المرور' })).toBeInTheDocument()
    unmount()
    await act(() => i18n.changeLanguage('en'))
    render(<Copyable />)
    expect(screen.getByRole('button', { name: 'Copy password' })).toBeInTheDocument()
  })

  it('has no accessibility violations after a copy', async () => {
    const user = userEvent.setup()
    vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    const { container } = render(<Copyable />)
    await user.click(screen.getByRole('button', { name: 'Copier le mot de passe' }))
    await screen.findByRole('status')

    expect((await axe.run(container)).violations).toEqual([])
  })

  it('has no copy button unless asked', () => {
    render(<Form />)
    expect(screen.queryByRole('button', { name: 'Copier le mot de passe' })).not.toBeInTheDocument()
  })
})
