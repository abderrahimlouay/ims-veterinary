import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

describe('AppLayout', () => {
  it('shows the user and logs out to the login page', async () => {
    serveMe(aUser())
    let loggedOut = false
    server.use(
      http.post('/api/auth/logout', () => {
        loggedOut = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const router = renderApp('/')

    expect(await screen.findByText('Amina Benali')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Se déconnecter' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Connexion' })).toBeInTheDocument()
    expect(loggedOut).toBe(true)
    expect(router.state.location.pathname).toBe('/login')
  })

  it('goes to the login page when the session expires during use', async () => {
    let expired = false
    server.use(
      http.get('/api/auth/me', () => (expired ? problem(401, 'AUTH_REQUIRED') : HttpResponse.json(aUser()))),
      http.post('/api/auth/change-password', () => {
        expired = true
        return problem(401, 'AUTH_REQUIRED')
      }),
    )
    const router = renderApp('/account')

    await userEvent.type(await screen.findByLabelText('Mot de passe actuel'), 'Tall-cedar-river-2026')
    await userEvent.type(screen.getByLabelText('Nouveau mot de passe'), 'Quiet-harbour-lantern-7')
    await userEvent.type(screen.getByLabelText('Confirmer le nouveau mot de passe'), 'Quiet-harbour-lantern-7')
    await userEvent.click(screen.getByRole('button', { name: 'Changer le mot de passe' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Connexion' })).toBeInTheDocument()
    expect(router.state.location.search).toBe(`?redirect=${encodeURIComponent('/account')}`)
  })
})
