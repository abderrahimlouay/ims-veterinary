import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { i18n } from '../lib/i18n'
import { aUser, serveMe } from '../test/auth'
import { renderApp as renderAt } from '../test/render'

describe('application shell', () => {
  beforeEach(() => {
    serveMe(aUser())
  })

  afterEach(async () => {
    await i18n.changeLanguage('fr')
  })

  it('renders the layout with landmarks and the home page in French', async () => {
    renderAt('/')

    expect(await screen.findByRole('banner')).toHaveTextContent('IMS Vétérinaire')
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeInTheDocument()
  })

  it('shows the not-found page for an unknown route', async () => {
    renderAt('/nowhere')

    expect(await screen.findByRole('heading', { level: 1, name: 'Page introuvable' })).toBeInTheDocument()
  })

  it('lets the user switch to Arabic, which turns the page right-to-left', async () => {
    renderAt('/')

    await userEvent.selectOptions(await screen.findByLabelText('Langue'), 'ar')

    expect(await screen.findByRole('heading', { level: 1, name: 'لوحة القيادة' })).toBeInTheDocument()
    expect(document.documentElement.dir).toBe('rtl')
  })
})
