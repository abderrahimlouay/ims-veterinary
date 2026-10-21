import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { describe, expect, it } from 'vitest'
import { aUser, serveMe } from '../test/auth'
import { renderApp } from '../test/render'
import { RequirePermission } from './guards'
import { AppProviders } from './providers'

describe('route guards', () => {
  it('sends anonymous users to the login page with a return target', async () => {
    serveMe(null)

    const router = renderApp('/account?tab=1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Connexion' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.search).toBe(`?redirect=${encodeURIComponent('/account?tab=1')}`)
  })

  it('keeps a user with a temporary password on the account page (ADR-039)', async () => {
    serveMe(aUser({ mustChangePassword: true }))

    const router = renderApp('/')

    expect(await screen.findByRole('heading', { level: 1, name: 'Mon compte' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/account')
  })

  it('shows the forbidden page in place when a permission is missing', async () => {
    serveMe(aUser({ permissions: ['sale.read'] }))
    const router = createMemoryRouter([
      { path: '/', element: <RequirePermission code="audit.read">secret page</RequirePermission> },
    ])

    render(
      <AppProviders>
        <RouterProvider router={router} />
      </AppProviders>,
    )

    expect(await screen.findByRole('heading', { level: 1, name: 'Accès refusé' })).toBeInTheDocument()
    expect(screen.queryByText('secret page')).not.toBeInTheDocument()
  })
})
